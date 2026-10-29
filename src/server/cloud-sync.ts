import { readSyncDocuments,syncFingerprint,applySyncDocuments,type SyncDocuments } from '../store/sync.ts';
import { stores } from './stores.ts';
import { isTurnRunning } from './turn-liveness.ts';
import { compactionInFlight } from './compaction.ts';
import { extractionStatuses } from '../agent/subagents/extraction.ts';
import { emit } from '../core/events.ts';

function idle() {
  if(extractionStatuses().length)return false;
  return stores.chatStore.listChats().every(c=>!isTurnRunning(c.id)&&!compactionInFlight(c.id)&&stores.jobStore.listJobs(c.id).every(j=>j.status!=='running'));
}
export function cloudSyncSnapshot() {
  if(!idle())return Response.json({error:'Learning work is running; sync will wait.'},{status:409});
  const documents=readSyncDocuments();
  return Response.json({documents,fingerprint:syncFingerprint(documents)},{headers:{'Cache-Control':'no-store'}});
}
export function cloudSyncApply(body:{documents?:SyncDocuments;expected?:string}) {
  // There is deliberately no await between these guards and the synchronous store transaction.
  // A turn cannot start in this process between the check and import.
  if(!idle())return Response.json({error:'Learning work is running; sync will wait.'},{status:409});
  if(!body.documents||typeof body.documents!=='object'||Array.isArray(body.documents)||body.expected!==syncFingerprint(readSyncDocuments()))return Response.json({error:'The local library changed. Sync will retry.'},{status:409});
  const changed=applySyncDocuments(body.documents);
  for(const chatId of changed)emit('record-changed',{chatId});
  emit('chats-changed',{});emit('activity-changed',{});
  return Response.json({ok:true},{headers:{'Cache-Control':'no-store'}});
}
