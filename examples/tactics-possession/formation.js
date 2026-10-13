// SPDX-License-Identifier: GPL-2.0-or-later
// Declarative positions only: no scripts, accounts or second movement authority.
export const FORMATION_SCHEMA='remix-formation/1';
const fields=(o,keys)=>o&&typeof o==='object'&&!Array.isArray(o)&&Object.keys(o).length===keys.length&&keys.every(k=>Object.hasOwn(o,k));
export function validateFormation(input){
 if(!fields(input,['schema','id','name','slots'])||input.schema!==FORMATION_SCHEMA||
   typeof input.id!=='string'||!/^[a-z][a-z0-9-]{0,31}$/.test(input.id)||
   typeof input.name!=='string'||!/^[A-Za-z0-9][A-Za-z0-9 .'-]{0,39}$/.test(input.name)||
   !Array.isArray(input.slots)||input.slots.length!==8)throw Error('A formation needs a name and eight valid positions.');
 const slots=input.slots.map(p=>{if(!fields(p,['x','z'])||!Number.isFinite(p.x)||!Number.isFinite(p.z)||Math.abs(p.x)>8||Math.abs(p.z)>8)throw Error('Formation positions must stay within eight metres.');return {x:p.x,z:p.z};});
 for(let i=0;i<slots.length;i++)for(let j=i+1;j<slots.length;j++)if(Math.hypot(slots[i].x-slots[j].x,slots[i].z-slots[j].z)<1.1)throw Error('Leave room between guardians: at least 1.1 metres.');
 return {schema:FORMATION_SCHEMA,id:input.id,name:input.name,slots};
}
export function parseFormation(text){if(typeof text!=='string'||text.length>4096)throw Error('Formation files must be smaller than 4 KB.');return validateFormation(JSON.parse(text));}
export const formations=Object.freeze([
 validateFormation({schema:FORMATION_SCHEMA,id:'shield-line',name:'Shield line',slots:Array.from({length:8},(_,i)=>({x:(i%4-1.5)*1.15,z:Math.floor(i/4)*1.15}))}),
 validateFormation({schema:FORMATION_SCHEMA,id:'spear-wedge',name:'Spear wedge',slots:[{x:0,z:0},{x:-.8,z:1.6},{x:.8,z:1.6},{x:-1.6,z:3.2},{x:0,z:3.2},{x:1.6,z:3.2},{x:-.8,z:4.8},{x:.8,z:4.8}]}),
 validateFormation({schema:FORMATION_SCHEMA,id:'gate-column',name:'Gate column',slots:Array.from({length:8},(_,i)=>({x:(i%2-.5)*1.3,z:Math.floor(i/2)*1.5}))})
]);
export function applyFormation(world,pack){const accepted=validateFormation(pack);if(world.status!=='playing')throw Error('Start a new encounter to change its formation.');world.formation=accepted;return accepted;}
export function formationLink(base,pack){const url=new URL(base);url.hash='formation='+encodeURIComponent(JSON.stringify(validateFormation(pack)));return url.href;}
export function formationFromLink(hash){if(!hash.startsWith('#formation='))return null;if(hash.length>8192)throw Error('Formation link is too long.');return parseFormation(decodeURIComponent(hash.slice(11)));}
