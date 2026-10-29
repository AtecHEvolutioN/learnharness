import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {startMobileBridge, projectSession, mobileInput}=require('../../electron/mobile-bridge.cjs');
const token='a'.repeat(64), desktopSecret='b'.repeat(64);
const fixture=()=>({id:'chat_1',title:'Test lesson',createdAt:new Date().toISOString(),messages:[
  {id:'u1',role:'user',content:'Teach me gravity',timestamp:1},
  {id:'a1',role:'assistant',content:[{type:'text',text:'Consider an orbit.'},{type:'toolCall',id:'q1',name:'quiz',arguments:{question:'Which path?',options:[{label:'Straight',value:'straight'},{label:'Curve',value:'curve'}],correctAnswer:'straight',explanation:'Inertia preserves velocity.',nodeId:'a'}}],timestamp:2}
],tree:{nodes:[{id:'a',title:'Inertia'}],edges:[]}});

test('projection hides quiz answers and internal reasoning before submission',()=>{
  const record=fixture();record.messages[1].content.unshift({type:'thinking',thinking:'private reasoning'});
  const view=projectSession(record);
  assert.equal(view.question.id,'q1');
  assert.equal(JSON.stringify(view).includes('correctAnswer'),false);
  assert.equal(JSON.stringify(view).includes('Inertia preserves'),false);
  assert.equal(JSON.stringify(view).includes('private reasoning'),false);
  assert.equal(view.plan[0].title,'Inertia');
});
test('answers are graded by upstream shared logic; stale questions fail',()=>{
  const input=mobileInput(fixture(),{type:'answer',toolCallId:'q1',answer:{values:['straight'],confidence:70,thinking:'No force'}});
  assert.equal(input.answer.correct,true);assert.equal(input.answer.confidence,70);
  assert.throws(()=>mobileInput(fixture(),{type:'answer',toolCallId:'old',answer:{values:['straight']}}),/changed/);
  assert.throws(()=>mobileInput(fixture(),{type:'text',text:'skip'}),/current question/);
  assert.throws(()=>mobileInput(fixture(),{type:'answer',toolCallId:'q1',answer:{values:['invalid']}}),/Unknown/);
});
test('bridge requires a token, rejects web origins, exposes only allowed routes, and deduplicates writes',async()=>{
  let writes=0;let record=fixture();
  const backend=http.createServer(async(req,res)=>{
    assert.equal(req.headers['x-learnharness-desktop'],desktopSecret);
    res.setHeader('Content-Type','application/json');
    if(req.url==='/api/chats'&&req.method==='POST'){writes++;res.end(JSON.stringify({id:'chat_new',title:'New'}));return;}
    if(req.url==='/api/chats'){res.end(JSON.stringify({chats:[{id:'chat_1',title:'Test'}]}));return;}
    if(req.url==='/api/chats/chat_1'){res.end(JSON.stringify(record));return;}
    if(req.url==='/api/graph'){res.end(JSON.stringify({nodes:[],edges:[]}));return;}
    res.writeHead(404);res.end('{}');
  });
  await new Promise(r=>backend.listen(0,'127.0.0.1',r));
  const bridge=await startMobileBridge({desktopUrl:`http://127.0.0.1:${backend.address().port}`,desktopSecret,token,port:0});
  const base=`http://127.0.0.1:${bridge.address().port}`;
  const headers={Authorization:`Bearer ${token}`};
  try {
    assert.equal((await fetch(base+'/v1/status')).status,401);
    assert.equal((await fetch(base+'/v1/status',{headers:{...headers,Origin:'https://attacker.example'}})).status,401);
    assert.equal((await fetch(base+'/api/keys',{headers})).status,404);
    assert.equal((await fetch(base+'/v1/status',{headers})).status,200);
    const view=await (await fetch(base+'/v1/sessions/chat_1',{headers})).json();assert.equal(view.question.id,'q1');
    const request={method:'POST',headers:{...headers,'X-Request-ID':'request-1234567890'},body:JSON.stringify({title:'New'})};
    assert.equal((await fetch(base+'/v1/sessions',request)).status,200);
    assert.equal((await fetch(base+'/v1/sessions',request)).status,200);
    assert.equal(writes,1);
    assert.equal((await fetch(base+'/v1/sessions',{...request,body:JSON.stringify({title:'Changed'})})).status,409);
    assert.equal((await fetch(base+'/v1/sessions/chat_1',{method:'DELETE',headers})).status,404);
  } finally {bridge.closeAllConnections();backend.closeAllConnections();await Promise.all([new Promise(r=>bridge.close(r)),new Promise(r=>backend.close(r))]);}
});
