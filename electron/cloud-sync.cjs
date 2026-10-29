'use strict';
const {app,safeStorage,dialog,clipboard}=require('electron');
const fs=require('node:fs');
const path=require('node:path');
const {synchronize,emptySync,hash,canonical}=require('./cloud-sync-core.cjs');
function atomic(file,value){const tmp=file+'.tmp';const fd=fs.openSync(tmp,'w',0o600);try{fs.writeFileSync(fd,value);fs.fsyncSync(fd);}finally{fs.closeSync(fd);}fs.renameSync(tmp,file);}
function createCloudSync(desktopUrl,desktopSecret) {
  const root=app.getPath('userData'),configFile=path.join(root,'cloud-sync.bin'),stateFile=path.join(root,'cloud-sync-state.json');
  let busy=false,lastRemote=0,lastFingerprint='',status='Private cloud sync is not configured',closed=false;
  function config() {
    if(!safeStorage.isEncryptionAvailable())throw new Error('Secure credential storage is unavailable');
    const bootstrap=path.join(root,'cloud-sync-bootstrap.json');
    if(fs.existsSync(bootstrap)) {
      const next=JSON.parse(fs.readFileSync(bootstrap,'utf8'));validateConfig(next);
      if(fs.existsSync(configFile)&&safeStorage.decryptString(fs.readFileSync(configFile))!==JSON.stringify(next))throw new Error('A different sync vault is already configured');
      atomic(configFile,safeStorage.encryptString(JSON.stringify(next)));fs.unlinkSync(bootstrap);
    }
    if(!fs.existsSync(configFile))return null;
    const value=JSON.parse(safeStorage.decryptString(fs.readFileSync(configFile)));validateConfig(value);return value;
  }
  function validateConfig(c) {
    const u=new URL(c.url);
    if(c.version!==1||u.protocol!=='https:'||u.username||u.password||u.search||u.hash||! /^[a-f0-9]{64}$/.test(c.token)||Buffer.from(c.key,'base64').length!==32)throw new Error('Invalid private sync configuration');
  }
  async function local(body) {
    const response=await fetch(new URL('/api/cloud-sync',desktopUrl),{method:body?'POST':'GET',headers:{'x-learnharness-desktop':desktopSecret,'Content-Type':'application/json'},redirect:'error',signal:AbortSignal.timeout(30000),body:body?JSON.stringify(body):undefined});
    if(!response.ok)throw new Error(response.status===409?'Waiting for your current PC work to finish':'The PC sync endpoint is unavailable');
    return response.json();
  }
  async function sync(force=false) {
    if(busy||closed)return;
    busy=true;
    try {
      const c=config();if(!c)return;
      const snapshot=await local();
      if(!force&&snapshot.fingerprint===lastFingerprint&&Date.now()-lastRemote<300000)return;
      status='Syncing encrypted lessons…';
      const state=fs.existsSync(stateFile)?JSON.parse(fs.readFileSync(stateFile,'utf8')):emptySync('PC');
      const result=await synchronize(snapshot.documents,state,c.key,{
        cloud:async body=>{
          const response=await fetch(c.url.replace(/\/$/,'')+'/api/sync',{method:'POST',headers:{Authorization:`Bearer ${c.token}`,'Content-Type':'application/json'},body:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(30000)});
          if(!response.ok)throw new Error(`Cloud sync is unavailable (${response.status}); your local progress is safe`);
          return response.json();
        },
        saveState:async next=>atomic(stateFile,JSON.stringify(next)),
        apply:async documents=>local({expected:snapshot.fingerprint,documents})
      });
      lastRemote=Date.now();
      const applied=Object.fromEntries(Object.entries(result.documents).filter(([,value])=>!value._deleted));
      lastFingerprint=await hash(canonical(applied));
      status=result.conflicts?`Synced. ${result.conflicts} conflict copies were kept.`:'All changes synced';
    }catch(e){status=e.message??'Sync will retry. Your local progress is safe.';}
    finally{busy=false;}
  }
  async function show() {
    await sync(true);
    const c=config();
    const choice=await dialog.showMessageBox({type:'info',title:'LearnHarness private sync',message:status,
      detail:'Lessons and progress are encrypted on this device before upload. Your phone runs its own ChatGPT sessions; this PC can be off. Each app syncs when open and online.\n\nThe setup code contains your private vault key. Share it only with your own LearnHarness app.',
      buttons:c?['Copy phone setup code','Sync now','Close']:['Close'],defaultId:c?2:0,cancelId:c?2:0});
    if(c&&choice.response===0)clipboard.writeText('lh-sync-v1:'+Buffer.from(JSON.stringify(c)).toString('base64'));
    if(c&&choice.response===1)await sync(true);
  }
  const timer=setInterval(()=>void sync(),30000);timer.unref();
  void sync(true);
  app.on('before-quit',()=>{closed=true;clearInterval(timer);});
  return {show,sync,status:()=>status};
}
module.exports={createCloudSync};
