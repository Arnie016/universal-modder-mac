// SPDX-License-Identifier: GPL-2.0-or-later
import {formations,validateFormation} from './formation.js';
// Original host simulation. 0 A.D. supplies squad commands, not host physics or AI.
export const obstacles=[{x:-10,z:1,w:4,d:13},{x:10,z:1,w:4,d:13},{x:-6,z:13,w:4,d:3},{x:6,z:13,w:4,d:3}];
export function createWorld(epoch=1){
  let id=0;const units=[];
  for(let i=0;i<8;i++)units.push({id:++id,team:1,x:(i%4-1.5)*2.1,z:22+Math.floor(i/4)*2,hp:180,maxHp:180,cooldown:0,stance:'defensive',order:null});
  for(let i=0;i<12;i++)units.push({id:++id,team:2,x:(i%6-2.5)*3,z:-22-Math.floor(i/6)*3,hp:150,maxHp:150,activeAt:Math.floor(i/4)*25,cooldown:0,stance:'aggressive',order:{type:'attack-walk',x:0,z:25}});
  return {formation:validateFormation(formations[0]),epoch,sequence:0,applied:[],tick:0,time:0,units,view:'overview',possessed:1,status:'playing',core:300,kills:0,events:[],lastStrike:0};
}
const distance=(a,b)=>Math.hypot(a.x-b.x,a.z-b.z);
export function blocked(x,z,r=.52){return Math.abs(x)>40||Math.abs(z)>38||obstacles.some(o=>Math.abs(x-o.x)<o.w/2+r&&Math.abs(z-o.z)<o.d/2+r);}
export function clearSight(a,b){const n=Math.ceil(distance(a,b)*3);for(let i=1;i<n;i++){const t=i/n;if(blocked(a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t,0))return false;}return true;}
export function formationDestinations(w,count,x,z){
  if(!Number.isInteger(count)||count<1||count>8||!Number.isFinite(x)||!Number.isFinite(z)||blocked(x,z))throw Error('Choose an open part of the battlefield.');
  const destinations=validateFormation(w.formation||formations[0]).slots.slice(0,count).map(p=>({x:x+p.x,z:z+p.z}));
  if(destinations.some(p=>blocked(p.x,p.z)))throw Error('This formation touches a wall or edge. Choose more open ground.');
  return destinations;
}
export function applyOrder(w,e){
  if(e.v!==1||e.epoch!==w.epoch||!Number.isSafeInteger(e.seq)||e.seq<1||w.applied.includes(e.seq)||![1,2].includes(e.owner))return false;
  const c=e.command;if(!['walk','attack-walk','attack','stance'].includes(c?.type)||c.queued||c.pushFront||!Array.isArray(c.entities)||!c.entities.length||c.entities.length>32||new Set(c.entities).size!==c.entities.length)return false;
  const targets=c.entities.map(id=>w.units.find(u=>u.id===id));if(targets.some(u=>!u||u.team!==e.owner||u.hp<=0))return false;
  if(['walk','attack-walk'].includes(c.type)&&(!Number.isFinite(c.x)||!Number.isFinite(c.z)||blocked(c.x,c.z)))return false;
  if(c.type==='attack'&&!w.units.some(u=>u.id===c.target&&u.team!==e.owner&&u.hp>0))return false;
  if(c.type==='stance'&&!['aggressive','defensive','passive','standground'].includes(c.name))return false;
  let destinations=[];
  if(['walk','attack-walk'].includes(c.type)){try{destinations=formationDestinations(w,targets.length,c.x,c.z);}catch{return false;}}
  w.applied.push(e.seq);w.sequence=Math.max(w.sequence,e.seq);
  targets.forEach((u,i)=>{if(c.type==='stance'){u.stance=c.name;if(c.name==='standground'){u.order=null;u.path=[];delete u.pathTarget;}}else{u.order={...c,...destinations[i]};u.path=[];delete u.pathTarget;}});
  return true;
}
export function switchView(w,id=w.possessed){if(w.status!=='playing')return false;const u=w.units.find(x=>x.id===id&&x.team===1&&x.hp>0);if(!u)return false;w.possessed=id;w.view=w.view==='overview'?'soldier':'overview';return true;}
function move(u,dx,dz,dt,speed=4.8){const len=Math.hypot(dx,dz);if(len<.15)return;const nx=dx/len*speed*dt,nz=dz/len*speed*dt;if(!blocked(u.x+nx,u.z))u.x+=nx;if(!blocked(u.x,u.z+nz))u.z+=nz;}
export function route(a,b){
  // Navigation stores coordinates, never live entity references. Otherwise two
  // units chasing each other can form a cycle and make battle saves unreadable.
  a={x:a.x,z:a.z};b={x:b.x,z:b.z};
  if(clearSight(a,b))return [b];
  const nodes=[a,b];for(const o of obstacles)for(const sx of [-1,1])for(const sz of [-1,1]){const p={x:o.x+sx*(o.w/2+.85),z:o.z+sz*(o.d/2+.85)};if(!blocked(p.x,p.z))nodes.push(p);}
  const dist=nodes.map(()=>Infinity),prev=[],done=new Set();dist[0]=0;
  while(done.size<nodes.length){let i=-1;for(let j=0;j<nodes.length;j++)if(!done.has(j)&&(i<0||dist[j]<dist[i]))i=j;if(i<0||!Number.isFinite(dist[i]))break;if(i===1)break;done.add(i);for(let j=0;j<nodes.length;j++){if(j===i||done.has(j)||!clearSight(nodes[i],nodes[j]))continue;const d=dist[i]+distance(nodes[i],nodes[j]);if(d<dist[j]){dist[j]=d;prev[j]=i;}}}
  if(!Number.isFinite(dist[1]))return [];const out=[];for(let i=1;i!==0;i=prev[i])out.unshift(nodes[i]);return out;
}
function navigate(u,target,w,dt){
  if(!u.path||!u.pathTarget||distance(target,u.pathTarget)>2||w.tick%45===0){u.path=route(u,target);u.pathTarget={x:target.x,z:target.z};}
  while(u.path.length&&distance(u,u.path[0])<.4)u.path.shift();const p=u.path[0];if(p)move(u,p.x-u.x,p.z-u.z,dt);
}
function hit(w,a,b,amount){b.hp=Math.max(0,b.hp-amount*(b.team===1&&b.stance==='defensive'?.6:1));a.cooldown=a.id===w.possessed&&w.view==='soldier'?.6:1.25;w.events.push({type:'hit',x:b.x,z:b.z,tick:w.tick});if(!b.hp){w.events.push({type:'death',id:b.id,team:b.team,x:b.x,z:b.z,tick:w.tick});if(b.team===2)w.kills++;}}
export function step(w,dt=1/60,input={}){
  if(w.status!=='playing')return;dt=Math.max(0,Math.min(.05,dt));w.tick++;w.time+=dt;w.events=[];
  for(const u of w.units.filter(u=>u.hp>0&&(u.activeAt||0)<=w.time)){
    if(u.hp<=0)continue;
    u.cooldown=Math.max(0,u.cooldown-dt);
    const enemies=w.units.filter(e=>e.hp>0&&e.team!==u.team&&(e.activeAt||0)<=w.time).sort((a,b)=>distance(u,a)-distance(u,b));
    const nearest=enemies[0];
    if(w.view==='soldier'&&u.id===w.possessed){
      move(u,input.dx||0,input.dz||0,dt,input.sprint?7:5);
      if(input.strike&&u.cooldown===0&&nearest&&distance(u,nearest)<3.6&&clearSight(u,nearest))hit(w,u,nearest,55);
      continue;
    }
    // A retreat/move is explicit intent. Attack-walk may seek combat; walk
    // must reach its destination before automatic defensive pursuit resumes.
    if(u.order?.type==='walk'){
      if(distance(u,u.order)<.4){u.order=null;u.path=[];delete u.pathTarget;}
      else{navigate(u,u.order,w,dt);continue;}
    }
    const explicit=w.units.find(e=>e.id===u.order?.target&&e.hp>0);
    const target=explicit||(u.stance!=='passive'&&nearest&&distance(u,nearest)<(u.team===2?40:u.stance==='defensive'?5:10)?nearest:null);
    if(target&&clearSight(u,target)&&distance(u,target)<1.85){if(u.cooldown===0)hit(w,u,target,u.team===1?18:10);}
    else if(target&&u.stance!=='standground'){
      navigate(u,target,w,dt);
    }
    else if(u.order&&Number.isFinite(u.order.x))navigate(u,u.order,w,dt);
    if(u.team===2&&distance(u,{x:0,z:29})<3){w.core=Math.max(0,w.core-dt*9);}
  }
  // One host owns body separation. Units cannot all stack on the same target.
  const alive=w.units.filter(u=>u.hp>0&&(u.activeAt||0)<=w.time);for(let i=0;i<alive.length;i++)for(let j=i+1;j<alive.length;j++){const a=alive[i],b=alive[j],d=distance(a,b);if(d>=1.15)continue;const dx=d>.001?(a.x-b.x)/d:1,dz=d>.001?(a.z-b.z)/d:0,push=(1.15-d)*.5;for(const [u,s] of [[a,1],[b,-1]]){const x=u.x+dx*push*s,z=u.z+dz*push*s;if(!blocked(x,z)){u.x=x;u.z=z;}}}
  if(w.units.find(u=>u.id===w.possessed)?.hp<=0){const alive=w.units.find(u=>u.team===1&&u.hp>0);if(alive)w.possessed=alive.id;w.view='overview';}
  if(!w.units.some(u=>u.team===2&&u.hp>0))w.status='won';
  else if(w.core<=0||!w.units.some(u=>u.team===1&&u.hp>0)||w.time>=180)w.status='lost';
}
export function snapshot(w){return structuredClone(w);}
export function restore(data){if(data?.units?.length!==20||!['playing','won','lost'].includes(data.status)||data.units.some(u=>!Number.isFinite(u.x)||!Number.isFinite(u.z)||!Number.isFinite(u.hp)))throw Error('Invalid save');const copy=structuredClone(data);copy.formation=validateFormation(copy.formation||formations[0]);return copy;}
