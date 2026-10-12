// SPDX-License-Identifier: GPL-2.0-or-later
import { EntityCollection } from './vendor/0ad/entitycollection.js';
export const DONOR={name:'0 A.D. EntityCollection',commit:'78ca04310ebe9c02d8db22eb6bbd79ee0f3dfac9',license:'GPL-2.0-or-later'};
export function donorOrder(world,player,ids,action,payload={}){
  if(![1,2].includes(player)||!Array.isArray(ids)||ids.length>32||!ids.length||new Set(ids).size!==ids.length)throw Error('Invalid owner or selection');
  for(const id of ids){const e=world.units.find(x=>x.id===id);if(!e||e.team!==player||e.hp<=0)throw Error('Selection is outside player ownership');}
  if(!['move','attackMove','attack','stance'].includes(action))throw Error('Unsupported donor capability');
  if(['move','attackMove'].includes(action)&&(!Number.isFinite(payload.x)||!Number.isFinite(payload.z)||Math.abs(payload.x)>45||Math.abs(payload.z)>45))throw Error('Order is outside battlefield');
  if(action==='attack'&&!world.units.some(e=>e.id===payload.target&&e.team!==player&&e.hp>0))throw Error('Invalid attack target');
  if(action==='stance'&&!['aggressive','defensive','passive','standground'].includes(payload.name))throw Error('Invalid stance');
  const commands=[];const hadEngine=Object.hasOwn(globalThis,'Engine'),hadPlayer=Object.hasOwn(globalThis,'PlayerID');
  const oldEngine=globalThis.Engine,oldPlayer=globalThis.PlayerID;
  // Upstream is trusted, pinned source. This synchronous binding is not a sandbox.
  globalThis.Engine={PostCommand(owner,command){if(owner!==player)throw Error('Owner mismatch');commands.push(structuredClone(command));}};
  globalThis.PlayerID=player;
  try{
    const c=new EntityCollection(null,new Map(ids.map(id=>[id,{id:()=>id}])));
    if(action==='move')c.move(payload.x,payload.z);
    if(action==='attackMove')c.attackMove(payload.x,payload.z,{attack:['Unit']},false);
    if(action==='attack')c.attack(payload.target);
    if(action==='stance')c.setStance(payload.name);
  }finally{if(hadEngine)globalThis.Engine=oldEngine;else delete globalThis.Engine;if(hadPlayer)globalThis.PlayerID=oldPlayer;else delete globalThis.PlayerID;}
  return commands.map(command=>({v:1,epoch:world.epoch,seq:++world.sequence,owner:player,command}));
}
