import {createHash,timingSafeEqual} from 'node:crypto';
const idPattern=/^[a-f0-9]{64}$/;
const response=(body,status=200)=>Response.json(body,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
/** Only opaque encrypted revisions enter this service. No inference, plaintext lessons or client key. */
export function createHandler(storage,tokenHash) {
  return async request=>{
    const auth=request.headers.get('authorization')??'';
    const actual=createHash('sha256').update(auth.startsWith('Bearer ')?auth.slice(7):'').digest();
    const expected=Buffer.from(tokenHash??'','hex');
    if(expected.length!==32 || !timingSafeEqual(actual,expected) || request.headers.has('origin'))return response({error:'Unauthorized'},401);
    if(request.method!=='POST')return response({error:'Method not allowed'},405);
    try {
      if(Number(request.headers.get('content-length'))>4_000_000)return response({error:'Request too large'},413);
      const reader=request.body?.getReader();if(!reader)return response({error:'Missing request'},400);
      const chunks=[];let size=0;
      while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>4_000_000){await reader.cancel();return response({error:'Request too large'},413);}chunks.push(value);}
      const input=JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if(input.op==='list') {
        if(input.cursor!==undefined&&(typeof input.cursor!=='string'||input.cursor.length>4096))return response({error:'Invalid cursor'},400);
        const page=await storage.list({prefix:'revisions/',limit:1000,cursor:input.cursor});
        return response({ids:page.blobs.map(b=>b.pathname.replace(/^revisions\//,'').replace(/\.json$/,'')).filter(id=>idPattern.test(id)),cursor:page.hasMore?page.cursor:undefined});
      }
      if(!idPattern.test(input.id??''))return response({error:'Invalid revision ID'},400);
      const pathname=`revisions/${input.id}.json`;
      if(input.op==='get') {
        const result=await storage.get(pathname,{access:'private',useCache:false});
        if(!result || result.statusCode!==200)return response({error:'Revision not found'},404);
        const blob=JSON.parse(await new Response(result.stream).text());return response({blob});
      }
      if(input.op==='put') {
        const b=input.blob;
        if(!b||b.version!==1||typeof b.iv!=='string'||! /^[A-Za-z0-9+/]{16}$/.test(b.iv)||typeof b.data!=='string'||b.data.length<24||b.data.length>3_900_000||! /^[A-Za-z0-9+/]+={0,2}$/.test(b.data))return response({error:'Invalid encrypted revision'},400);
        try {await storage.put(pathname,JSON.stringify({version:1,iv:b.iv,data:b.data}),{access:'private',contentType:'application/json',addRandomSuffix:false,allowOverwrite:false});}
        catch(error){if(error.name!=='BlobPathnameAlreadyExistsError')throw error;}
        return response({ok:true});
      }
      return response({error:'Unknown operation'},400);
    } catch(error) {
      if(error instanceof SyntaxError)return response({error:'Invalid JSON'},400);
      console.error('Encrypted sync operation failed',{type:error?.name??'Error'});
      return response({error:'Sync is temporarily unavailable. Local progress is safe.'},503);
    }
  };
}
