import test from 'node:test';
import assert from 'node:assert/strict';
import {newRecord,run,projection,wireInput,migrateLegacy} from '../portable/engine.test-bundle.mjs';
import {emptySync,synchronize,seal,unseal,canonical} from '../portable/sync.test-bundle.mjs';
const complete=(name,args,id='call_1')=>({status:'completed',model:'fixture',output:[{type:'function_call',call_id:id,name,arguments:JSON.stringify(args)}]});
test('native grading survives a phone turn and produces a resumable desktop transcript',async()=>{
  let saved;
  const r=await run(newRecord('Addition'),{type:'text',text:'Teach me addition'}, {persist:async v=>{saved=structuredClone(v)},infer:async()=>complete('probe',{question:'What is 1 + 1?',options:[{label:'2',value:'a'},{label:'3',value:'b'}],explanation:'One plus one is two.',correctAnswer:'a'})});
  assert.equal(r.view.question.kind,'quiz');
  assert.equal(r.record.messages.at(-1).role,'assistant');
  const answered=await run(r.record,{type:'answer',toolCallId:'call_1',answer:{values:['a'],confidence:90,thinking:'Two units'}}, {persist:async v=>{saved=structuredClone(v)},infer:async()=>({status:'completed',model:'fixture',output:[{type:'message',content:[{type:'output_text',text:'Exactly.'}]}]})});
  const result=answered.record.messages.find(m=>m.role==='toolResult');
  assert.equal(result.toolCallId,'call_1');assert.equal(result.isError,false);
  assert.equal(answered.view.question,null);
  assert.ok(wireInput(answered.record).some(x=>x.type==='function_call_output'));
  assert.deepEqual(saved.view,projection(answered.record));
});
test('provider failure preserves learner input and never fabricates a successful response',async()=>{
  let saved;
  await assert.rejects(run(newRecord('A'),{type:'text',text:'Explain A'}, {persist:async v=>{saved=structuredClone(v)},infer:async()=>{throw new Error('network down')}}),/network down/);
  assert.equal(saved.record.messages.length,1);assert.equal(saved.record.messages[0].content,'Explain A');assert.match(saved.record.turnError,/network/);
});
test('invalid model tool arguments become a recoverable error, not a broken question',async()=>{
  let calls=0;
  const out=await run(newRecord('A'),{type:'text',text:'Explain A'}, {persist:async()=>{},infer:async()=>++calls===1?complete('probe',{question:'missing options'}):({status:'completed',model:'fixture',output:[{type:'message',content:[{type:'output_text',text:'Let us start again.'}]}]})});
  assert.equal(out.view.question,null);assert.ok(out.record.messages.some(m=>m.role==='toolResult'&&m.isError));
});
function cloud(){const blobs=new Map();return async r=>{if(r.op==='put'){if(!blobs.has(r.id))blobs.set(r.id,structuredClone(r.blob));return {ok:true}}if(r.op==='list')return {ids:[...blobs.keys()]};if(r.op==='get')return {blob:blobs.get(r.id)};throw Error('bad op')}}
const key=btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))));
function device(name,transport){let state=emptySync(name),docs={};return {get docs(){return docs},get state(){return state},setDocs:d=>{docs=structuredClone(d)},sync:()=>synchronize(docs,state,key,{cloud:transport,saveState:async s=>{state=structuredClone(s)},apply:async d=>{docs=structuredClone(Object.fromEntries(Object.entries(d).filter(([,v])=>!v._deleted)))}})}}
test('phone and PC sync while never online at the same time',async()=>{
 const transport=cloud(),pc=device('PC',transport),phone=device('Phone',transport);
 pc.setDocs({'chat:a':{id:'a',title:'Original',messages:[]}});await pc.sync();await phone.sync();
 phone.setDocs({'chat:a':{...phone.docs['chat:a'],title:'Continued on phone'}});await phone.sync();await pc.sync();
 assert.equal(pc.docs['chat:a'].title,'Continued on phone');
});
test('concurrent edits converge and retain both complete versions',async()=>{
 const transport=cloud(),pc=device('PC',transport),phone=device('Phone',transport);
 pc.setDocs({'chat:a':{id:'a',title:'Original',messages:[]}});await pc.sync();await phone.sync();
 pc.setDocs({'chat:a':{id:'a',title:'PC edit',messages:['PC work']}});
 phone.setDocs({'chat:a':{id:'a',title:'Phone edit',messages:['Phone work']}});
 await pc.sync();await phone.sync();await pc.sync();await phone.sync();
 assert.equal(Object.keys(pc.docs).length,2);assert.equal(canonical(pc.docs),canonical(phone.docs));
 assert.deepEqual(Object.values(pc.docs).map(x=>x.messages[0]).sort(),['PC work','Phone work']);
});
test('authenticated encryption rejects a changed blob, key, or revision identity',async()=>{
 const id='a'.repeat(64),event={version:1,id,entity:'chat:a',parents:[],payload:{id:'a',title:'Private'},device:'Phone'};
 const blob=await seal(event,key);assert.ok(!JSON.stringify(blob).includes('Private'));assert.deepEqual(await unseal(id,blob,key),event);
 await assert.rejects(unseal('b'.repeat(64),blob,key));
 await assert.rejects(unseal(id,{...blob,data:blob.data.slice(0,-4)+'AAAA'},key));
});
test('failed upload keeps the durable outbox for retry',async()=>{
 let saved=emptySync('Phone');const documents={'chat:a':{id:'a',title:'Keep me'}};
 await assert.rejects(synchronize(documents,saved,key,{cloud:async()=>{throw Error('offline')},saveState:async s=>{saved=structuredClone(s)},apply:async()=>{throw Error('must not apply')}}),/offline/);
 assert.equal(saved.events.length,1);assert.equal(saved.uploaded.length,0);
});

test('deleted sessions stay deleted across restarts and another device sync',async()=>{
 const transport=cloud(),pc=device('PC',transport),phone=device('Phone',transport);
 pc.setDocs({'chat:a':{id:'a',title:'To delete'}});await pc.sync();await phone.sync();
 pc.setDocs({});await pc.sync();await phone.sync();await pc.sync();await phone.sync();
 assert.deepEqual(pc.docs,{});assert.deepEqual(phone.docs,{});
});

test('an offline edit survives a concurrent delete',async()=>{
 const transport=cloud(),pc=device('PC',transport),phone=device('Phone',transport);
 pc.setDocs({'chat:a':{id:'a',title:'Original'}});await pc.sync();await phone.sync();
 pc.setDocs({});phone.setDocs({'chat:a':{id:'a',title:'New phone work'}});
 await pc.sync();await phone.sync();await pc.sync();
 assert.equal(pc.docs['chat:a'].title,'New phone work');assert.equal(canonical(pc.docs),canonical(phone.docs));
});

test('old phone lessons retain their full original data and displayable history',()=>{
 const old={id:'legacy-a',title:'Saved lesson',mode:'learn',createdAt:1,messages:[{id:'u',role:'user',text:'Help',thinking:'My reasoning',confidence:4},{id:'a',role:'assistant',text:'Lesson',turn:{question:'Why?',plan:[{id:'one',title:'First',prerequisites:[]}],concepts:[{id:'c',title:'A fact',statement:'A proposition'}]}}]};
 const migrated=migrateLegacy(old);
 assert.deepEqual(migrated.record.legacyAndroid,old);assert.equal(migrated.view.id,'phone_legacy-a');
 assert.ok(migrated.view.messages.some(m=>m.text.includes('My reasoning')));assert.ok(migrated.view.messages.some(m=>m.text.includes('Why?')));
 assert.equal(migrated.record.messages.at(-1).details.block.nodes[0].title,'First');
});

test('teaching tool text appears in the canonical desktop and native views',async()=>{
 let calls=0;
 const out=await run(newRecord('A'),{type:'text',text:'Teach A'},{persist:async()=>{},infer:async()=>++calls===1?complete('send_to_user',{message:'Visible teaching'}):complete('probe',{question:'What?',options:[{label:'A',value:'a'},{label:'B',value:'b'}],correctAnswer:'a',explanation:'A'},'call_2')});
 assert.ok(out.view.messages.some(m=>m.text.includes('Visible teaching')));
});

test('reasoning, tool namespaces and response IDs survive phone and desktop continuation',async()=>{
 const reasoning={type:'reasoning',id:'rs_test',summary:[],encrypted_content:'opaque-encrypted-fixture'};
 const tool={type:'function_call',id:'fc_test',call_id:'call_test',name:'probe',namespace:'learnharness',arguments:JSON.stringify({question:'One plus one?',options:[{label:'2',value:'a'},{label:'3',value:'b'}],correctAnswer:'a',explanation:'Two units.'})};
 let request;
 const asked=await run(newRecord('A'),{type:'text',text:'Teach A'},{model:'fixture',persist:async()=>{},infer:async v=>{request=v;return {status:'completed',model:'fixture-snapshot',output:[reasoning,tool]}}});
 assert.deepEqual(request.include,['reasoning.encrypted_content']);
 assert.equal(asked.record.messages.at(-1).model,'fixture');
 assert.equal(asked.view.question.id,'call_test|fc_test');
 const answered=await run(asked.record,{type:'answer',toolCallId:asked.view.question.id,answer:{values:['a'],confidence:90,thinking:'Two units'}},{model:'fixture',persist:async()=>{},infer:async v=>{request=v;return {status:'completed',model:'fixture',output:[{type:'message',id:'msg_test',phase:'final_answer',content:[{type:'output_text',text:'Correct.'}]}]}}});
 assert.deepEqual(request.input.find(x=>x.type==='reasoning'),reasoning);
 assert.deepEqual(request.input.find(x=>x.type==='function_call'),tool);
 assert.equal(request.input.find(x=>x.type==='function_call_output').call_id,'call_test');
 const same=wireInput(answered.record,'fixture');
 assert.equal(same.at(-1).id,'msg_test');assert.equal(same.at(-1).phase,'final_answer');
 for(const changed of [wireInput(answered.record,'different-model'),wireInput({...answered.record,messages:answered.record.messages.map(m=>m.role==='assistant'?{...m,provider:'openai'}:m)},'fixture')]) {
   assert.ok(!changed.some(x=>x.type==='reasoning'));
   const call=changed.find(x=>x.type==='function_call');assert.equal(call.id,undefined);assert.equal(call.namespace,undefined);
   assert.equal(changed.at(-1).phase,undefined);assert.equal(changed.at(-1).content,'Correct.');
 }
});

test('sync compacts uploaded history without losing offline edits or new-device recovery',async()=>{
 const transport=cloud(),pc=device('PC',transport),phone=device('Phone',transport);
 for(let i=0;i<25;i++){pc.setDocs({'chat:a':{id:'a',title:'Version '+i,messages:['history '.repeat(2000),String(i)]}});await pc.sync();}
 assert.equal(pc.state.events.length,25);
 assert.equal(pc.state.events.filter(e=>e.payload!==undefined).length,1);
 assert.ok(JSON.stringify(pc.state).length<40000);
 await phone.sync();assert.equal(phone.docs['chat:a'].title,'Version 24');
 phone.setDocs({'chat:a':{id:'a',title:'Phone edit',messages:['phone']}});await phone.sync();await pc.sync();
 assert.equal(pc.docs['chat:a'].title,'Phone edit');
});
