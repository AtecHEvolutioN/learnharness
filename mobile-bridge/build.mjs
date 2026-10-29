import { build } from 'esbuild';
import path from 'node:path';
await build({ entryPoints: ['src/index.ts'], outfile: '../electron/mobile-bridge.cjs', bundle: true,
  platform: 'node', format: 'cjs', target: 'node22', nodePaths: [path.resolve('node_modules')] });
