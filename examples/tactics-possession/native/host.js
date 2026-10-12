// SPDX-License-Identifier: GPL-2.0-or-later
// The native renderer calls this trusted runtime. It owns no second physics world.
import {GameBridge,browserHost} from '../bridge.js';
import {createWorld,applyOrder,step,snapshot,restore} from '../simulation.js';
globalThis.structuredClone ??= value => JSON.parse(JSON.stringify(value));
export const nativeHost={...browserHost,id:'native-valley'};
let world=createWorld();
const bridge=new GameBridge({descriptor:nativeHost,observe:()=>world,submit:e=>applyOrder(world,e),advance:n=>{for(let i=0;i<n;i++)step(world);},snapshot:()=>snapshot(world)});
const output=()=>JSON.stringify(snapshot(world));
const parse=json=>{if(typeof json!=='string'||json.length>524288)throw Error('Invalid message size');return JSON.parse(json);};
const api={
  capabilities:()=>JSON.stringify({host:nativeHost,match:bridge.match,donor:bridge.match.donor}),
  observe:output,
  order:json=>{
    const a=parse(json);if(a.epoch!==world.epoch||world.status!=='playing')throw Error('Stale or completed battle');
    const receipts=bridge.order(1,a.ids,a.action,a.payload);
    return JSON.stringify({receipts,battle:snapshot(world)});
  },
  submit:json=>{const e=parse(json);return JSON.stringify({accepted:e.owner===1&&applyOrder(world,e),battle:snapshot(world)});},
  tick:json=>{
    const input=parse(json);
    if(!Number.isFinite(input.dx??0)||!Number.isFinite(input.dz??0)||Math.abs(input.dx??0)>2||Math.abs(input.dz??0)>2)throw Error('Invalid input');
    step(world,1/60,input);return output();
  },
  advance:json=>JSON.stringify(bridge.step(parse(json).ticks)),
  viewpoint:json=>{
    const a=parse(json),u=world.units.find(u=>u.id===a.id&&u.team===1&&u.hp>0);
    if(a.epoch!==world.epoch||!u||!['overview','soldier'].includes(a.view)||world.status!=='playing')throw Error('Invalid viewpoint');
    world.possessed=u.id;world.view=a.view;return output();
  },
  reset:()=>{world=createWorld(world.epoch+1);return output();},
  save:()=>JSON.stringify({schema:'remix-battle/1',recipe:'tactics-possession',world:snapshot(world)}),
  load:json=>{
    const a=parse(json),w=a.world;
    if(a.schema!=='remix-battle/1'||a.recipe!=='tactics-possession'||!w||
      !Number.isSafeInteger(w.epoch)||w.epoch<1||!Number.isSafeInteger(w.tick)||w.tick<0||
      !Number.isSafeInteger(w.sequence)||w.sequence<0||!Array.isArray(w.applied)||w.applied.length>4096||
      w.applied.some(n=>!Number.isSafeInteger(n)||n<1)||!Array.isArray(w.events)||
      !Number.isFinite(w.time)||w.time<0||w.time>181||!Number.isFinite(w.core)||w.core<0||w.core>300||
      !Number.isInteger(w.kills)||w.kills<0||w.kills>12||!Number.isFinite(w.lastStrike)||
      !['overview','soldier'].includes(w.view)||!Number.isInteger(w.possessed)||w.possessed<1||w.possessed>8||
      !Array.isArray(w.units)||w.units.length!==20||new Set(w.units.map(u=>u.id)).size!==20||
      w.units.some(u=>!u||!Number.isInteger(u.id)||u.id<1||u.id>20||u.team!==(u.id<=8?1:2)||
        !Number.isFinite(u.x)||!Number.isFinite(u.z)||Math.abs(u.x)>40||Math.abs(u.z)>38||u.hp<0||u.hp>u.maxHp||u.maxHp!==(u.team===1?180:150)||
        (u.team===2&&u.activeAt!==Math.floor((u.id-9)/4)*25)||
        !Number.isFinite(u.cooldown)||u.cooldown<0||!['defensive','aggressive','passive','standground'].includes(u.stance)||
        (u.path&&(!Array.isArray(u.path)||u.path.length>64||!u.path.every(p=>p&&Number.isFinite(p.x)&&Number.isFinite(p.z)&&Math.abs(p.x)<=40&&Math.abs(p.z)<=38)))||
        (u.pathTarget&&(!Number.isFinite(u.pathTarget.x)||!Number.isFinite(u.pathTarget.z)))||
        (u.order&&(!['walk','attack-walk','attack'].includes(u.order.type)||
          (u.order.type==='attack'?!Number.isInteger(u.order.target)||u.order.target<1||u.order.target>20:
          !Number.isFinite(u.order.x)||!Number.isFinite(u.order.z)||Math.abs(u.order.x)>45||Math.abs(u.order.z)>43)))))throw Error('Invalid battle save');
    world=restore(w);return output();
  }
};
globalThis.nativeRemix=api;
export {api};
