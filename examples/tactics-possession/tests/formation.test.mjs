// SPDX-License-Identifier: GPL-2.0-or-later
import test from 'node:test';
import assert from 'node:assert/strict';
import {formations,validateFormation,parseFormation,applyFormation,formationLink,formationFromLink} from '../formation.js';
import {createWorld,applyOrder,step,snapshot,restore} from '../simulation.js';
import {GameBridge,browserHost} from '../bridge.js';
import {api} from '../native/host.js';
const binding=w=>new GameBridge({descriptor:browserHost,observe:()=>w,submit:e=>applyOrder(w,e),advance:n=>{for(let i=0;i<n;i++)step(w);},snapshot:()=>snapshot(w)});
test('layout validation rejects scripts, bad coordinates and overlap without changing the world',()=>{
 const w=createWorld(),before=snapshot(w);
 for(const pack of [ {...formations[1],script:'alert(1)'},{...formations[1],name:'<script>'},{...formations[1],slots:formations[1].slots.map(()=>({x:0,z:0}))},{...formations[1],slots:formations[1].slots.map((p,i)=>i? p:{x:9,z:0})} ]){
  assert.throws(()=>applyFormation(w,pack));assert.deepEqual(snapshot(w),before);
 }
 assert.throws(()=>parseFormation('x'.repeat(4097)));
});
test('creator layout changes actual destinations and movement from unchanged donor commands',()=>{
 const w=createWorld();w.units.filter(u=>u.team===2).forEach(u=>{u.stance='passive';u.order=null;});
 const pack=validateFormation({...formations[1],id:'creator-pincer',name:'Creator pincer',slots:Array.from({length:8},(_,i)=>({x:(i-3.5)*1.3,z:0}))});
 applyFormation(w,pack);const bridge=binding(w);
 const receipts=bridge.order(1,[1,2,3,4,5,6,7,8],'move',{x:0,z:18});
 assert.equal(receipts[0].command.type,'walk');assert.equal(receipts[0].seq,w.sequence);
 assert.deepEqual(w.units.slice(0,8).map(u=>({x:u.order.x,z:u.order.z})),pack.slots.map(p=>({x:p.x,z:18})));
 bridge.step(600);
 for(let i=0;i<8;i++)assert.ok(Math.hypot(w.units[i].x-pack.slots[i].x,w.units[i].z-18)<.75);
 assert.deepEqual(restore(snapshot(w)),snapshot(w));
});
test('a clear centre with a blocked formation slot rejects the whole order atomically',()=>{
 const w=createWorld();applyFormation(w,{...formations[0],id:'wide-line',name:'Wide line',slots:Array.from({length:8},(_,i)=>({x:(i-3.5)*2,z:0}))});
 const before=snapshot(w);assert.throws(()=>binding(w).order(1,[1,2,3,4,5,6,7,8],'move',{x:0,z:13}));
 assert.deepEqual(w,before);
});
test('share links round-trip only validated layout data',()=>{
 const url=formationLink('https://example.com/games/tactics/?x=1',formations[1]);
 assert.deepEqual(formationFromLink(new URL(url).hash),formations[1]);
 assert.equal(formationFromLink('#other'),null);
 assert.throws(()=>formationFromLink('#formation='+encodeURIComponent(JSON.stringify({...formations[1],id:'../escape'}))));
});
test('native adapter imports the same layout and saves its real order assignments',()=>{
 JSON.parse(api.reset());api.formation(JSON.stringify(formations[2]));
 const w=JSON.parse(api.observe());
 api.order(JSON.stringify({epoch:w.epoch,ids:[1,2,3,4,5,6,7,8],action:'move',payload:{x:0,z:18}}));
 const saved=api.save();api.reset();api.load(saved);
 assert.equal(api.save(),saved);
 const loaded=JSON.parse(api.observe());assert.equal(loaded.formation.id,'gate-column');
 assert.deepEqual(loaded.units.slice(0,8).map(u=>({x:u.order.x,z:u.order.z})),formations[2].slots.map(p=>({x:p.x,z:p.z+18})));
 const before=api.save();assert.throws(()=>api.formation(JSON.stringify({...formations[1],slots:[]})));assert.equal(api.save(),before);
});
