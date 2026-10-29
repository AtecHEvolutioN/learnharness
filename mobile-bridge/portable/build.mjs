import { build } from 'esbuild';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const resolve = createRequire(import.meta.url).resolve;
const read = async p => (await fs.readFile(path.join(root,p),'utf8')).replaceAll('\r\n','\n');
const practice = await read('src/agent/tools/practice.ts');
const schemas = ['problemInputObject','concludeInputObject'].map(name => {
  const start = practice.indexOf(`export const ${name} =`);
  const end = practice.indexOf('\nexport type ',start);
  if(start < 0 || end < 0) throw new Error(`Missing upstream schema ${name}`);
  return practice.slice(start,end);
}).join('\n');
const prompts = {};
for(const kind of ['prober','teacher','practice']) {
  const files = (await fs.readdir(path.join(root,'src/agent/prompts',kind))).filter(f=>/^\d.*\.md$/.test(f) && !/jobs|demos|files/.test(f)).sort();
  const shared = (await fs.readdir(path.join(root,'src/agent/prompts/shared'))).filter(f=>/^\d.*\.md$/.test(f) && !/learner-files/.test(f) && (kind !== 'practice' || ['10-voice.md','15-accuracy.md','16-mental-model.md','40-formatting.md'].includes(f))).sort();
  prompts[kind] = (await Promise.all([...(kind==='practice'?[]:['src/agent/system-prompt.md']),...files.map(f=>`src/agent/prompts/${kind}/${f}`),...shared.map(f=>`src/agent/prompts/shared/${f}`)].map(read))).join('\n\n---\n\n');
}
const options = { absWorkingDir:root,entryPoints:[path.join(here,'engine.ts')],bundle:true,target:'es2020',nodePaths:[path.join(root,'mobile-bridge/node_modules')],
  alias:{typebox:resolve('typebox'),'typebox/value':resolve('typebox/value')},
  plugins:[{name:'upstream-contracts',setup(b){
    b.onResolve({filter:/^upstream-practice$/},()=>({path:'practice',namespace:'generated'}));
    b.onLoad({filter:/.*/,namespace:'generated'},()=>({contents:`import { Type } from 'typebox';\n${schemas}`,loader:'ts',resolveDir:path.join(root,'mobile-bridge')}));
    b.onResolve({filter:/^upstream-prompts$/},()=>({path:'prompts',namespace:'prompts'}));
    b.onLoad({filter:/.*/,namespace:'prompts'},()=>({contents:`export default ${JSON.stringify(prompts)}`,loader:'js'}));
  }}] };
const assets = path.join(root,'android/app/src/main/assets'); await fs.mkdir(assets,{recursive:true});
await fs.copyFile(path.join(root,'electron/mobile-bridge.LICENSE.txt'),path.join(assets,'THIRD-PARTY-NOTICES.txt'));
await build({...options,platform:'browser',format:'iife',globalName:'HarnessCore',outfile:path.join(assets,'engine.js')});
await build({...options,platform:'node',format:'esm',outfile:path.join(here,'engine.test-bundle.mjs')});
await build({absWorkingDir:root,entryPoints:[path.join(here,'sync.ts')],bundle:true,platform:'node',format:'esm',target:'node22',outfile:path.join(here,'sync.test-bundle.mjs')});
await build({absWorkingDir:root,entryPoints:[path.join(here,'sync.ts')],bundle:true,platform:'node',format:'cjs',target:'node22',outfile:path.join(root,'electron/cloud-sync-core.cjs')});
