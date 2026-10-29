import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {test,after} from 'node:test';

const root=fs.mkdtempSync(path.join(os.tmpdir(),'lh-sync-test-'));
process.env.LH_DATA_DIR=root;
const {applySyncDocuments,readSyncDocuments,syncFingerprint}=await import('../src/store/sync.ts');
after(()=>fs.rmSync(root,{recursive:true,force:true}));
const record=(title:string)=>({version:1,id:'fixture',title,createdAt:new Date(0).toISOString(),updatedAt:new Date(0).toISOString(),messages:[{id:'user',role:'user',content:'Preserve this input',timestamp:0}]});

test('sync imports complete sessions and backs up replaced and deleted originals',()=>{
 applySyncDocuments({'chat:fixture':record('Original')});
 applySyncDocuments({'chat:fixture':record('Continued')});
 const backups=fs.readdirSync(path.join(root,'sync-backups')).flatMap(dir=>fs.readdirSync(path.join(root,'sync-backups',dir)).map(file=>fs.readFileSync(path.join(root,'sync-backups',dir,file),'utf8')));
 assert.ok(backups.some(text=>JSON.parse(text).title==='Original'));
 assert.equal((readSyncDocuments()['chat:fixture'] as {title:string}).title,'Continued');
 applySyncDocuments({'chat:fixture':{_deleted:true,id:'fixture'}});
 assert.equal(readSyncDocuments()['chat:fixture'],undefined);
});

test('a corrupt ledger or unsafe path rejects the whole batch without replacing a lesson',()=>{
 applySyncDocuments({'chat:fixture':record('Keep')});
 const before=syncFingerprint(readSyncDocuments());
 assert.throws(()=>applySyncDocuments({'chat:fixture':record('Overwrite'),'ledger:nodes':[{}]}),/ledger/);
 assert.throws(()=>applySyncDocuments({'chat:../outside':record('Bad')}),/id/);
 assert.equal(syncFingerprint(readSyncDocuments()),before);
});

test('sync refuses a newer graph format before touching files',()=>{
 fs.mkdirSync(path.join(root,'graph'),{recursive:true});
 fs.writeFileSync(path.join(root,'graph','format.json'),'{"version":999}');
 assert.throws(()=>applySyncDocuments({'chat:fixture':record('Bad')}),/older than/);
 fs.unlinkSync(path.join(root,'graph','format.json'));
});
