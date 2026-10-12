// SPDX-License-Identifier: GPL-2.0-or-later
import {donorOrder,DONOR} from './adapter.js';
export const browserHost={id:'browser-valley',envelopeVersion:1,coordinateSystem:'right-handed-y-up',distanceUnit:'metre',tickHz:60,commands:['walk','attack-walk','attack','stance'],authority:{movement:'host',collision:'host',damage:'host',persistence:'host'}};
export function matchHost(host){
 const missing=[];
 for(const [key,value] of Object.entries({envelopeVersion:1,coordinateSystem:'right-handed-y-up',distanceUnit:'metre',tickHz:60}))if(host?.[key]!==value)missing.push(`${key}: requires ${value}`);
 for(const command of browserHost.commands)if(!host?.commands?.includes(command))missing.push(`command: ${command}`);
 for(const field of Object.keys(browserHost.authority))if(host?.authority?.[field]!=='host')missing.push(`single host authority: ${field}`);
 return {declaredCompatible:missing.length===0,missing,donor:DONOR,hostId:host?.id||null,gameplayProofRequired:true};
}
// Trusted adapter callbacks are supplied by each host. Contract matching is not
// permission to run arbitrary contributed code, nor proof of native game hooks.
export class GameBridge{
 constructor(binding){this.binding=binding;this.match=matchHost(binding.descriptor);if(!this.match.declaredCompatible)throw Error(this.match.missing.join('; '));for(const fn of ['observe','submit','advance','snapshot'])if(typeof binding[fn]!=='function')throw Error(`Missing adapter callback: ${fn}`);}
 order(owner,ids,action,payload){if(ids.length>8)throw Error('Selection budget exceeded');const receipts=donorOrder(this.binding.observe(),owner,ids,action,payload);for(const receipt of receipts)if(!this.binding.submit(receipt))throw Error('Host rejected command');return receipts;}
 step(ticks){if(!Number.isInteger(ticks)||ticks<0||ticks>600)throw Error('Tick budget exceeded');this.binding.advance(ticks);return this.binding.snapshot();}
 observe(){return this.binding.snapshot();}
}
