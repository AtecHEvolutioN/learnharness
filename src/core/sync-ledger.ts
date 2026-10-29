/** Validate the existing graph ledger contracts before a sync replaces local files. */
export function validateSyncLedger(name:string,value:unknown):asserts value is Record<string,unknown>[] {
  const string=(v:unknown)=>typeof v==='string';
  const strings=(v:unknown)=>Array.isArray(v)&&v.every(string);
  const provenance=(v:unknown)=>Array.isArray(v)&&v.every(p=>p&&typeof p==='object'&&string(p.chatId)&&string(p.messageId));
  if(!['nodes','chains','sessions','coverage'].includes(name)||!Array.isArray(value)||value.length>200000)throw new Error('Invalid synced ledger');
  for(const row of value) {
    if(!row||typeof row!=='object'||Array.isArray(row))throw new Error('Invalid ledger entry');
    const valid=name==='nodes'?string(row.id)&&string(row.name)&&string(row.statement)&&strings(row.aliases)&&string(row.createdAt)&&provenance(row.provenance):
      name==='chains'?string(row.id)&&strings(row.premises)&&row.premises.length>0&&string(row.conclusion)&&string(row.createdAt)&&provenance(row.provenance):
      name==='sessions'?string(row.chatId)&&string(row.foldedAt):
      string(row.chatId)&&string(row.fp)&&Number.isFinite(row.tokens)&&row.tokens>=0&&string(row.foldedAt);
    if(!valid)throw new Error('Invalid '+name+' ledger entry; local knowledge was kept');
  }
}
