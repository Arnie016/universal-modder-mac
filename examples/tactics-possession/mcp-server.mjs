// SPDX-License-Identifier: GPL-2.0-or-later
import {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';
import {StdioServerTransport} from '@modelcontextprotocol/sdk/server/stdio.js';
import {z} from 'zod';
import {createWorld,applyOrder,step,switchView,snapshot} from './simulation.js';
import {DONOR} from './adapter.js';
import {GameBridge,browserHost} from './bridge.js';
let world=createWorld();const bridge=new GameBridge({descriptor:browserHost,observe:()=>world,submit:e=>applyOrder(world,e),advance:n=>{for(let i=0;i<n;i++)step(world);},snapshot:()=>snapshot(world)});const server=new McpServer({name:'tactics-possession-lab',version:'0.1.0'});
const result=value=>({content:[{type:'text',text:JSON.stringify(value)}]});
server.registerTool('inspect',{description:'Read the isolated headless battle. This does not connect to the browser game or access accounts.',annotations:{readOnlyHint:true}},async()=>result({donor:DONOR,contract:bridge.match,world:bridge.observe()}));
server.registerTool('order',{description:'Issue a validated squad command to the isolated battle.',inputSchema:{ids:z.array(z.number().int().min(1).max(8)).min(1).max(8),action:z.enum(['move','attackMove','attack','stance']),x:z.number().min(-40).max(40).optional(),z:z.number().min(-38).max(38).optional(),target:z.number().int().min(9).max(20).optional(),name:z.enum(['aggressive','defensive','passive','standground']).optional()}},async ({ids,action,...payload})=>{try{const receipts=bridge.order(1,ids,action,payload);return result({receipts});}catch(e){return {isError:true,...result({error:e.message})};}});
server.registerTool('step',{description:'Advance the isolated battle by up to 600 fixed ticks.',inputSchema:{ticks:z.number().int().min(1).max(600)}},async({ticks})=>{return result(bridge.step(ticks));});
server.registerTool('possess',{description:'Switch the isolated battle view to the same guardian or return to overview.',inputSchema:{id:z.number().int().min(1).max(8)}},async({id})=>result({changed:switchView(world,id),world:snapshot(world)}));
server.registerTool('reset',{description:'Reset only this isolated headless battle.'},async()=>{world=createWorld(world.epoch+1);return result({epoch:world.epoch});});
await server.connect(new StdioServerTransport());
