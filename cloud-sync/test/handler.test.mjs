import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createHandler} from '../lib/handler.js';
const secret='fixture'.repeat(12),hash=createHash('sha256').update(secret).digest('hex');
function request(body,headers={}){return new Request('https://sync.test/api/sync',{method:'POST',headers:{Authorization:`Bearer ${secret}`,...headers},body:JSON.stringify(body)});}
test('unauthorized, browser-origin and traversal requests cannot touch storage',async()=>{
 const handler=createHandler(new Proxy({},{get(){throw Error('must not touch storage')}}),hash);
 assert.equal((await handler(request({op:'list'},{Authorization:'Bearer wrong'}))).status,401);
 assert.equal((await handler(request({op:'list'},{Origin:'https://untrusted.test'}))).status,401);
 assert.equal((await handler(request({op:'get',id:'../../secret'}))).status,400);
});
test('only bounded encrypted envelopes are accepted, with overwrite disabled',async()=>{
 let seen;
 const handler=createHandler({put:async(...args)=>{seen=args}},hash);
 const id='a'.repeat(64);
 assert.equal((await handler(request({op:'put',id,blob:{text:'plaintext'}}))).status,400);
 assert.equal((await handler(request({op:'put',id,blob:{version:1,iv:'A'.repeat(16),data:'B'.repeat(24)}}))).status,200);
 assert.equal(seen[2].access,'private');assert.equal(seen[2].allowOverwrite,false);
});
