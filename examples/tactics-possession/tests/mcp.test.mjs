import test from 'node:test';import assert from 'node:assert/strict';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';
test('real MCP initialize/list/call changes bounded battle state',async()=>{
 const client=new Client({name:'independent-bridge-verifier',version:'1.0'});
 const transport=new StdioClientTransport({command:process.execPath,args:['--import',new URL('../node-register.mjs',import.meta.url).pathname,new URL('../mcp-server.mjs',import.meta.url).pathname],stderr:'pipe'});
 try{await client.connect(transport);const list=await client.listTools();assert.deepEqual(list.tools.map(t=>t.name).sort(),['inspect','order','possess','reset','step']);
 const parse=r=>JSON.parse(r.content[0].text);const before=parse(await client.callTool({name:'inspect',arguments:{}}));
 const order=await client.callTool({name:'order',arguments:{ids:[1,2],action:'move',x:0,z:18}});assert.equal(order.isError,undefined);assert.equal(parse(order).receipts[0].command.type,'walk');
 const after=parse(await client.callTool({name:'step',arguments:{ticks:60}}));assert.ok(after.units[0].z<before.world.units[0].z);
 const owned=parse(await client.callTool({name:'possess',arguments:{id:1}}));assert.equal(owned.world.view,'soldier');assert.equal(owned.world.units[0].id,1);
 const invalid=await client.callTool({name:'step',arguments:{ticks:100000}});assert.equal(invalid.isError,true);
 const reset=parse(await client.callTool({name:'reset',arguments:{}}));assert.equal(reset.epoch,2);
 }finally{await client.close();}
});
