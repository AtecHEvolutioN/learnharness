// Prepare a reversible update for an existing LearnHarness 0.4.0 installation.
// This command reads the installed app and writes only to the requested staging/output directories.
import {extractAll, createPackage, extractFile} from '@electron/asar';
import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
const [source, stage, output] = process.argv.slice(2).map(x=>path.resolve(x));
if(!source || !stage || !output) throw new Error('Usage: node package-desktop-update.mjs <app.asar> <new-staging-directory> <output-directory>');
const pkg=JSON.parse(extractFile(source,'package.json').toString());
if(pkg.version !== '0.4.0') throw new Error(`Expected desktop 0.4.0; got ${pkg.version}. Review the integration before upgrading.`);
await mkdir(stage); await mkdir(output,{recursive:true});
extractAll(source,stage);
const mainFile=path.join(stage,'electron/main.cjs');
let main=await readFile(mainFile,'utf8');
function replaceOnce(before,after){if(main.split(before).length!==2)throw new Error('Desktop integration anchor missing or ambiguous');main=main.replace(before,after);}
replaceOnce('const PRODUCT_NAME = "LearnHarness";','const PRODUCT_NAME = "LearnHarness";\nlet mobileConnection;');
replaceOnce('{ role: "about" },','{ role: "about" },\n\t\t\t\t{ label: "Connect Android phone…", accelerator: "CommandOrControl+Shift+M", click: () => mobileConnection?.show() },');
replaceOnce('await waitUntilUp(url);','await waitUntilUp(url);\n\t\tmobileConnection = require("./mobile-connection.cjs").createMobileConnection(url, desktopAPISecret);');
await writeFile(mainFile,main);
for(const name of ['mobile-bridge.cjs','mobile-connection.cjs','mobile-bridge.LICENSE.txt']) await copyFile(path.resolve('../electron',name),path.join(stage,'electron',name));
const updated=path.join(output,'app.asar');await createPackage(stage,updated);
const hash=async f=>createHash('sha256').update(await readFile(f)).digest('hex');
await writeFile(path.join(output,'update-manifest.json'),JSON.stringify({desktopVersion:pkg.version,originalSha256:await hash(source),updatedSha256:await hash(updated)},null,2));
for(const name of ['Install-PC-Bridge.ps1','Restore-PC-App.ps1']) await copyFile(path.resolve(name),path.join(output,name));
console.log(`Prepared desktop update for ${pkg.version}; installed files were not changed.`);
