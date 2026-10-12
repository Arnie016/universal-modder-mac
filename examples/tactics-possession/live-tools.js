// SPDX-License-Identifier: GPL-2.0-or-later
import {blocked} from './simulation.js?v=0.1.1';
const object = (value, keys) => {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).some(key => !keys.includes(key))) throw Error('Invalid tool arguments');
};
const epochSchema = {type:'integer', minimum:1};
// A permission lease for a trusted host, not a sandbox for contributed code.
export class LiveBattleTools {
  constructor({context, bridge, ready, possess, notice=()=>{}, now=Date.now, changed=()=>{}}) {
    Object.assign(this, {context, bridge, ready, possess, notice, now, changed});
    this.lease = null;
  }
  get supported() { return typeof this.context?.registerTool === 'function'; }
  state() {
    if (this.lease && this.now() >= this.lease.until) this.revoke('expired');
    return {supported:this.supported, enabled:!!this.lease?.enabled,
      seconds:this.lease ? Math.max(0, Math.ceil((this.lease.until-this.now())/1000)) : 0};
  }
  revoke(reason='revoked') {
    const old=this.lease;
    this.lease=null;
    if (old) { old.enabled=false; old.controller.abort(); this.changed(reason); }
  }
  async enable() {
    if (!this.supported) throw Error('This browser does not support live agent tools. You can still play normally.');
    if (!this.ready()) throw Error('Enter the valley before enabling agent access.');
    this.revoke();
    const lease={controller:new AbortController(), enabled:false, until:this.now()+300000};
    this.lease=lease;
    const guard=(args, keys, signal, mutation=false)=>{
      if (this.now()>=lease.until) { if(this.lease===lease)this.revoke('expired'); }
      if (this.lease!==lease || !lease.enabled || signal?.aborted) throw Error('Agent access ended. Enable a new connection in the game.');
      object(args, keys);
      const world=this.bridge.observe();
      if (mutation && (!this.ready() || world.status!=='playing')) throw Error('Battle is not accepting orders.');
      if (mutation && args.epoch!==world.epoch) throw Error('Stale battle. Inspect the current battle before ordering.');
      return world;
    };
    const tools=[{
      name:'remix_inspect', description:'Read the actual open Tactics Possession battle: epoch, time, owned guardians, enemies, health and positions. No files or accounts.',
      inputSchema:{type:'object',properties:{},additionalProperties:false}, annotations:{readOnlyHint:true},
      execute:async(args,{signal}={})=>{const world=guard(args,[],signal);return JSON.stringify({host:'browser-valley',...world});}
    },{
      name:'remix_order', description:'Order living player guardians in the open battle through real 0 A.D. commands. Use the current epoch from remix_inspect. Move/attackMove use x,z; attack uses target; stance uses name. Never advances time or resets the battle.',
      inputSchema:{type:'object',properties:{epoch:epochSchema,ids:{type:'array',items:{type:'integer',minimum:1,maximum:8},minItems:1,maxItems:8,uniqueItems:true},action:{type:'string',enum:['move','attackMove','attack','stance']},payload:{type:'object',properties:{x:{type:'number',minimum:-40,maximum:40},z:{type:'number',minimum:-38,maximum:38},target:{type:'integer',minimum:9,maximum:20},name:{type:'string',enum:['aggressive','defensive','passive','standground']}},additionalProperties:false}},required:['epoch','ids','action','payload'],additionalProperties:false},
      execute:async(args,{signal}={})=>{
        const world=guard(args,['epoch','ids','action','payload'],signal,true);
        if(!Array.isArray(args.ids)||!args.ids.length||args.ids.length>8||new Set(args.ids).size!==args.ids.length||args.ids.some(id=>!Number.isInteger(id)||!world.units.some(u=>u.id===id&&u.team===1&&u.hp>0)))throw Error('Choose living owned guardians only.');
        const p=args.payload;
        if(['move','attackMove'].includes(args.action)){
          object(p,['x','z']);
          if(!Number.isFinite(p.x)||!Number.isFinite(p.z)||Math.abs(p.x)>40||Math.abs(p.z)>38||blocked(p.x,p.z))throw Error('Destination is blocked or outside the battle.');
        }else if(args.action==='attack'){
          object(p,['target']);
          if(!Number.isInteger(p.target)||!world.units.some(u=>u.id===p.target&&u.team===2&&u.hp>0&&(u.activeAt||0)<=world.time))throw Error('Choose a living raider in the current wave.');
        }else if(args.action==='stance'){
          object(p,['name']);if(!['aggressive','defensive','passive','standground'].includes(p.name))throw Error('Unknown stance.');
        }else throw Error('Unsupported battle action.');
        const receipts=this.bridge.order(1,args.ids,args.action,p);
        this.notice(`Agent ordered ${args.ids.length} guardian${args.ids.length===1?'':'s'}: ${args.action}.`);
        return JSON.stringify({accepted:true,receipts,battle:this.bridge.observe()});
      }
    },{
      name:'remix_possess',description:'Switch the open battle to command view or first person as one living owned guardian. The same entities and health remain. Use the current epoch from remix_inspect.',
      inputSchema:{type:'object',properties:{epoch:epochSchema,view:{type:'string',enum:['overview','soldier']},id:{type:'integer',minimum:1,maximum:8}},required:['epoch','view'],additionalProperties:false},
      execute:async(args,{signal}={})=>{
        const world=guard(args,['epoch','view','id'],signal,true);
        if(!['overview','soldier'].includes(args.view))throw Error('Unknown viewpoint.');
        const id=args.id??world.possessed;
        if(!Number.isInteger(id)||!world.units.some(u=>u.id===id&&u.team===1&&u.hp>0))throw Error('Choose a living owned guardian.');
        this.possess(args.view,id);this.notice(args.view==='soldier'?`Agent switched to guardian ${id}.`:'Agent returned to command view.');
        return JSON.stringify(this.bridge.observe());
      }
    }];
    try {
      for(const tool of tools) {
        const execute=tool.execute;
        const wrapped={...tool,execute:(args,options)=>execute(args,options??{})};
        await this.context.registerTool(wrapped,{signal:lease.controller.signal});
        if(this.lease!==lease)throw Error('Agent connection was cancelled.');
      }
      lease.enabled=true;this.changed('enabled');
    } catch(error) { if(this.lease===lease)this.revoke('failed'); throw error; }
    return this.state();
  }
}
