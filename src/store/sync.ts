import fs from 'node:fs';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { PATHS, LEDGER_FILES } from '../../config.ts';
import { projectView } from '../core/view.ts';
import { createTree } from '../core/plan-tree.ts';
import type { ChatRecord } from '../core/contracts.ts';
import { validateSyncLedger } from '../core/sync-ledger.ts';
import { assertGraphFormat,ensureGraphFormat } from './graph-format.ts';

export type SyncDocuments=Record<string,unknown>;
const ids=/^[A-Za-z0-9_-]{1,160}$/;
function canonical(value:unknown):string {
  if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';
  if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical((value as Record<string,unknown>)[k])).join(',')+'}';
  return JSON.stringify(value);
}
export function syncFingerprint(documents:SyncDocuments) {return createHash('sha256').update(canonical(documents)).digest('hex');}
function validateChat(value:unknown,id:string):ChatRecord {
  const r=value as ChatRecord;
  if(!r||r.id!==id||!ids.test(id)||typeof r.title!=='string'||!r.title.trim()||r.title.length>500||typeof r.createdAt!=='string'||typeof r.updatedAt!=='string'||!Array.isArray(r.messages)||r.messages.length>10000||(r.version??1)!==1)throw new Error('Unsupported synced session');
  const seen=new Set<string>();
  for(const m of r.messages){if(!m||typeof m.id!=='string'||seen.has(m.id)||!['user','assistant','toolResult','custom'].includes(m.role))throw new Error('Invalid synced transcript');seen.add(m.id);}
  if(r.tree)createTree(r.tree.nodes,r.tree.edges);
  projectView(r.messages);
  return r;
}
export function readSyncDocuments():SyncDocuments {
  assertGraphFormat();
  const docs:SyncDocuments={};
  if(fs.existsSync(PATHS.chats))for(const file of fs.readdirSync(PATHS.chats).filter(f=>f.endsWith('.json'))){
    const id=file.slice(0,-5);if(!ids.test(id))continue;
    docs['chat:'+id]=validateChat(JSON.parse(fs.readFileSync(path.join(PATHS.chats,file),'utf8')),id);
  }
  for(const [name,file]of Object.entries(LEDGER_FILES)) {
    const raw=fs.existsSync(file)?fs.readFileSync(file,'utf8'):'';
    docs['ledger:'+name]=raw.split(/\r?\n/).filter(l=>l.trim()).map(l=>JSON.parse(l));
  }
  return docs;
}
function targets(documents:SyncDocuments) {
  const files:Array<{file:string,text:string|null}>=[];
  for(const [entity,payload]of Object.entries(documents)) {
    if(entity.startsWith('chat:')) {
      const id=entity.slice(5);
      if(!ids.test(id))throw new Error('Invalid session id');
      if((payload as {_deleted?:boolean})?._deleted===true) {
        if((payload as {id?:string}).id!==id)throw new Error('Invalid deleted session');
        files.push({file:path.join(PATHS.chats,id+'.json'),text:null});continue;
      }
      const r=validateChat(payload,id);
      files.push({file:path.join(PATHS.chats,id+'.json'),text:JSON.stringify(r,null,'\t')+'\n'});
    } else if(entity.startsWith('ledger:')) {
      const name=entity.slice(7);if(!Object.hasOwn(LEDGER_FILES,name)||!Array.isArray(payload)||payload.length>200000)throw new Error('Invalid synced ledger');
      validateSyncLedger(name,payload);
      files.push({file:LEDGER_FILES[name as keyof typeof LEDGER_FILES],text:payload.map(x=>JSON.stringify(x)).join('\n')+(payload.length?'\n':'')});
    } else throw new Error('Unknown sync document');
  }
  return files;
}
/** Called synchronously after the server's idle/CAS guards. Every replaced file gets a local backup. */
export function applySyncDocuments(documents:SyncDocuments):string[] {
  assertGraphFormat();
  const files=targets(documents); // Validate the whole batch before touching any file.
  const parse=(file:string,text:string):unknown=>file.endsWith('.jsonl')?text.split(/\r?\n/).filter(Boolean).map(l=>JSON.parse(l)):JSON.parse(text);
  const changed=files.filter(({file,text})=>text===null?fs.existsSync(file):!fs.existsSync(file)||canonical(parse(file,fs.readFileSync(file,'utf8')))!==canonical(parse(file,text)));
  if(!changed.length)return [];
  if(changed.some(x=>x.file.endsWith('.jsonl')))ensureGraphFormat();
  const backup=path.join(PATHS.data,'sync-backups',new Date().toISOString().replace(/[:.]/g,'-')+'-'+randomUUID().slice(0,8));
  fs.mkdirSync(backup,{recursive:true});
  const staged:Array<{file:string,temp:string|null,old?:string}>=[];
  try {
    for(const {file,text}of changed) {
      fs.mkdirSync(path.dirname(file),{recursive:true});
      const old=fs.existsSync(file)?fs.readFileSync(file,'utf8'):undefined;
      if(old!==undefined)fs.writeFileSync(path.join(backup,path.basename(path.dirname(file))+'-'+path.basename(file)),old,{mode:0o600});
      const temp=text===null?null:file+'.sync-'+randomUUID()+'.tmp';
      if(temp!==null){const fd=fs.openSync(temp,'wx',0o600);try{fs.writeFileSync(fd,text!);fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}
      staged.push({file,temp,old});
    }
    for(const item of staged){if(item.temp===null)fs.unlinkSync(item.file);else fs.renameSync(item.temp,item.file);}
  } catch(error) {
    // A caught failure restores the prior files; an abrupt process exit still leaves backup copies.
    for(const item of staged){try{if(item.old!==undefined)fs.writeFileSync(item.file,item.old);else if(item.temp!==null&&!fs.existsSync(item.temp))fs.unlinkSync(item.file);}catch{/* originals remain in backup */}}
    throw error;
  } finally {for(const item of staged){try{if(item.temp)fs.unlinkSync(item.temp);}catch{/* already renamed */}}}
  return changed.filter(x=>path.dirname(x.file)===PATHS.chats).map(x=>path.basename(x.file,'.json'));
}
