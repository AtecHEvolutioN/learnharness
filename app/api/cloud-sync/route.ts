import {cloudSyncSnapshot,cloudSyncApply} from '@/src/server';
export function GET(){return cloudSyncSnapshot();}
export async function POST(request:Request){
  try {
    const raw=await request.text();
    if(raw.length>80_000_000)return Response.json({error:'Sync batch too large'},{status:413});
    return cloudSyncApply(JSON.parse(raw));
  } catch {return Response.json({error:'The synced library could not be validated. Existing data was kept.'},{status:400});}
}
