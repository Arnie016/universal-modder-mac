// SPDX-License-Identifier: GPL-2.0-or-later
// macOS CI runs the actual Apple JavaScriptCore adapter, not a Node substitute.
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const build=spawnSync(process.execPath,[fileURLToPath(new URL('./build.mjs',import.meta.url))],{encoding:'utf8'});
if(build.stderr)process.stderr.write(build.stderr);
if(build.status!==0)throw Error(build.stdout||'Native build failed');
const artifact=JSON.parse(build.stdout.trim());
const result=spawnSync(artifact.binary,['--verify'],{encoding:'utf8',timeout:60000});
if(result.stderr)process.stderr.write(result.stderr);
if(result.status!==0)throw Error(result.stdout||'Native runtime verifier failed');
const receipt=JSON.parse(result.stdout);
if(receipt.passed<18||receipt.runtime!=='JavaScriptCore')throw Error('Incomplete native proof');
console.log(JSON.stringify({artifact,receipt},null,2));
