import test from 'node:test';
import assert from 'node:assert/strict';
import {LiveBattleTools} from '../live-tools.js';
import {GameBridge,browserHost} from '../bridge.js';
import {createWorld,applyOrder,step,snapshot} from '../simulation.js';
function fixture(failAt=0){
  let world=createWorld(),clock=0,ready=true;
  const registered=new Map(),retained=new Map();
  const context={async registerTool(tool,{signal}){
    retained.set(tool.name,tool);
    if(failAt===retained.size)throw Error('Registration failed');
    registered.set(tool.name,tool);signal.addEventListener('abort',()=>registered.delete(tool.name),{once:true});
  }};
  const bridge=new GameBridge({descriptor:browserHost,observe:()=>world,submit:e=>applyOrder(world,e),advance:n=>{for(let i=0;i<n;i++)step(world);},snapshot:()=>snapshot(world)});
  const access=new LiveBattleTools({context,bridge,ready:()=>ready,now:()=>clock,possess:(view,id)=>{world.view=view;world.possessed=id;}});
  return {access,bridge,registered,retained,get world(){return world;},clock:n=>clock=n,ready:v=>ready=v,reset:()=>world=createWorld(world.epoch+1),call:(name,args)=>retained.get(name).execute(args)};
}
const order={epoch:1,ids:[1],action:'move',payload:{x:0,z:18}};
test('live tool commands mutate the exact host rendered by player controls',async()=>{
  const f=fixture();assert.equal(f.registered.size,0);await f.access.enable();
  assert.equal(f.registered.size,3);const receipt=JSON.parse(await f.call('remix_order',order));
  assert.equal(receipt.receipts[0].command.type,'walk');assert.equal(f.world.units[0].order.type,'walk');assert.equal(f.world.units[0].order.z,18);
  const before=f.world.units[0].z;f.bridge.step(60);const seen=JSON.parse(await f.call('remix_inspect',{}));
  assert.ok(seen.units[0].z<before);assert.equal(seen.units[0].z,f.world.units[0].z);
  f.world.units[0].hp=77;await f.call('remix_possess',{epoch:1,view:'soldier',id:1});
  assert.equal(f.world.units[0].hp,77);assert.equal(f.world.view,'soldier');
  await f.call('remix_possess',{epoch:1,view:'soldier',id:1});assert.equal(f.world.view,'soldier');
});
test('revocation unregisters tools and rejects retained callbacks after a new lease',async()=>{
  const f=fixture();await f.access.enable();const old=f.retained.get('remix_order');f.access.revoke();
  assert.equal(f.registered.size,0);await assert.rejects(old.execute(order),/access ended/);
  await f.access.enable();await assert.rejects(old.execute(order),/access ended/);
  assert.equal(JSON.parse(await f.call('remix_order',order)).accepted,true);
});
test('live combat observations and saves remain JSON-safe when units chase each other',async()=>{
  const f=fixture();await f.access.enable();
  for(let i=0;i<8;i++)f.bridge.step(600);
  const seen=JSON.parse(await f.call('remix_inspect',{}));
  assert.equal(seen.units.length,20);assert.ok(seen.kills>0);
  const saved=JSON.parse(JSON.stringify(snapshot(f.world)));
  assert.deepEqual(saved.units[0].path,f.world.units[0].path);
  for(const u of f.world.units)for(const point of u.path||[])assert.deepEqual(Object.keys(point).sort(),['x','z']);
});
test('expiry is enforced at call time without relying on UI timers',async()=>{
  const f=fixture();await f.access.enable();f.clock(300000);
  await assert.rejects(f.call('remix_order',order),/access ended/);assert.equal(f.registered.size,0);
});
test('stale epochs and paused/finished hosts reject mutations',async()=>{
  const f=fixture();await f.access.enable();f.reset();await assert.rejects(f.call('remix_order',order),/Stale battle/);
  f.ready(false);await assert.rejects(f.call('remix_order',{...order,epoch:2}),/not accepting/);
  assert.equal(JSON.parse(await f.call('remix_inspect',{})).epoch,2);
});
test('ownership, bounds, walls, inactive targets and extra fields do not mutate the host',async()=>{
  const f=fixture();await f.access.enable();const before=snapshot(f.world);
  const invalid=[{...order,ids:[9]},{...order,ids:[1,1]},{...order,payload:{x:41,z:0}},{...order,payload:{x:-10,z:0}},{...order,payload:{x:NaN,z:0}},{...order,action:'attack',payload:{target:13}},{...order,action:'stance',payload:{name:'invalid'}},{...order,payload:{x:0,z:18,cheat:true}},{...order,reset:true}];
  for(const args of invalid)await assert.rejects(f.call('remix_order',args));
  await assert.rejects(f.call('remix_possess',{epoch:1,view:'soldier',id:9}));
  assert.deepEqual(f.world,before);
});
test('partial registration rolls back and cancellation aborts a retained call',async()=>{
  const f=fixture(2);await assert.rejects(f.access.enable(),/Registration failed/);assert.equal(f.registered.size,0);
  await assert.rejects(f.call('remix_inspect',{}),/access ended/);
  const ok=fixture();await ok.access.enable();const controller=new AbortController();controller.abort();
  await assert.rejects(ok.retained.get('remix_order').execute(order,{signal:controller.signal}),/access ended/);
});
test('unsupported browsers and unopened battles cannot enable access',async()=>{
  const unsupported=new LiveBattleTools({context:null});assert.equal(unsupported.state().supported,false);
  await assert.rejects(unsupported.enable(),/does not support/);
  const f=fixture();f.ready(false);await assert.rejects(f.access.enable(),/Enter the valley/);assert.equal(f.registered.size,0);
});
