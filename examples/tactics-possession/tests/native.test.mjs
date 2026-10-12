// SPDX-License-Identifier: GPL-2.0-or-later
import test from 'node:test';
import assert from 'node:assert/strict';
import {api,nativeHost} from '../native/host.js';
const call=(name,args)=>JSON.parse(api[name](args===undefined?undefined:JSON.stringify(args)));
test('native host uses the donor bridge and preserves world identity across views',()=>{
 const initial=call('reset');
 assert.equal(nativeHost.authority.collision,'host');
 const result=call('order',{epoch:initial.epoch,ids:[1,2],action:'move',payload:{x:0,z:18}});
 assert.equal(result.receipts[0].command.type,'walk');
 call('advance',{ticks:60});
 const before=call('observe');assert.ok(before.units[0].z<initial.units[0].z);
 const firstPerson=call('viewpoint',{epoch:before.epoch,id:1,view:'soldier'});
 assert.deepEqual(firstPerson.units,before.units);
 const restoredView=call('viewpoint',{epoch:before.epoch,id:1,view:'overview'});
 assert.deepEqual(restoredView,before);
 const saved=api.save();call('reset');api.load(saved);assert.deepEqual(call('observe'),before);
});
test('native import and authority failures leave the battle unchanged',()=>{
 const w=call('reset'),saved=api.save();
 assert.throws(()=>call('order',{epoch:w.epoch,ids:[9],action:'move',payload:{x:0,z:18}}));
 assert.throws(()=>call('order',{epoch:w.epoch-1,ids:[1],action:'move',payload:{x:0,z:18}}));
 assert.throws(()=>call('tick',{dx:"not-a-number",dz:0}));
 const bad=JSON.parse(saved);bad.world.units[0].path={x:0,z:0};
 assert.throws(()=>api.load(JSON.stringify(bad)));assert.equal(api.save(),saved);
 bad.world.units[0].path=[];bad.world.kills=-1;
 assert.throws(()=>api.load(JSON.stringify(bad)));assert.equal(api.save(),saved);
 assert.throws(()=>api.load('x'.repeat(524289)));
 const result=call('submit',{v:1,epoch:w.epoch,seq:1,owner:1,command:{type:'walk',entities:[1],x:-10,z:0}});
 assert.equal(result.accepted,false);assert.deepEqual(result.battle,w);
});
