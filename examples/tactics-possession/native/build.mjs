// SPDX-License-Identifier: GPL-2.0-or-later
import {build} from 'esbuild';
import {mkdir,writeFile,copyFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import os from 'node:os';
import {createHash} from 'node:crypto';
if(process.platform!=='darwin')throw Error('The native host requires macOS and Apple command-line tools.');
const base=path.dirname(fileURLToPath(import.meta.url));
// File Provider folders can reattach Finder metadata and prevent app signing.
// Keep disposable build products in a checkout-specific local temporary folder.
const buildRoot=process.env.REMIX_NATIVE_BUILD_DIR || path.join(os.tmpdir(),'universal-modder-native-'+createHash('sha256').update(base).digest('hex').slice(0,12));
const app=path.join(buildRoot,'Tactics Possession.app');
const resources=path.join(app,'Contents','Resources'),bin=path.join(app,'Contents','MacOS');
await mkdir(resources,{recursive:true});await mkdir(bin,{recursive:true});
await build({entryPoints:[path.join(base,'host.js')],bundle:true,format:'iife',platform:'neutral',target:'es2020',outfile:path.join(resources,'host.js'),alias:{'simulation/ai/common-api/utils.js':path.join(base,'..','vendor','0ad','utils.js')},legalComments:'inline'});
const compile=spawnSync('xcrun',['swiftc','-parse-as-library','-swift-version','5','-target',(process.arch==='arm64'?'arm64':'x86_64')+'-apple-macos14.0','-O','-framework','AppKit','-framework','SceneKit','-framework','JavaScriptCore',path.join(base,'NativeHost.swift'),'-o',path.join(bin,'TacticsPossession')],{encoding:'utf8'});
if(compile.stderr)process.stderr.write(compile.stderr);if(compile.status!==0)process.exit(compile.status||1);
await writeFile(path.join(app,'Contents','Info.plist'),`<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleExecutable</key><string>TacticsPossession</string><key>CFBundleIdentifier</key><string>org.universalmodder.tactics.nativeprototype</string><key>CFBundleName</key><string>Tactics Possession</string><key>CFBundleVersion</key><string>1</string><key>CFBundleShortVersionString</key><string>0.1.2</string><key>NSHighResolutionCapable</key><true/><key>LSMinimumSystemVersion</key><string>14.0</string><key>NSPrincipalClass</key><string>NSApplication</string></dict></plist>`);
await copyFile(path.join(base,'..','LICENSE'),path.join(resources,'LICENSE'));
// Remove inherited Finder metadata only from our generated app before signing.
await copyFile(path.join(base,'..','vendor','0ad','LICENSE.md'),path.join(resources,'0AD-LICENSE.md'));
await copyFile(path.join(base,'..','vendor','0ad','license_gpl-2.0.txt'),path.join(resources,'GPL-2.0.txt'));
const clean=spawnSync('xattr',['-cr',app],{encoding:'utf8'});
if(clean.status!==0)throw Error(clean.stderr||'Generated app metadata cleanup failed');
const signed=spawnSync('codesign',['--force','--sign','-',app],{encoding:'utf8'});
if(signed.status!==0)throw Error(signed.stderr||'Local ad-hoc signing failed');
console.log(JSON.stringify({app,binary:path.join(bin,'TacticsPossession'),runtime:'JavaScriptCore',renderer:'SceneKit',signing:'AD_HOC_LOCAL_ONLY',notarized:false}));
