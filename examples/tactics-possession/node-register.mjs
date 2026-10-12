import { registerHooks } from 'node:module';
registerHooks({resolve(s,c,n){return s==='simulation/ai/common-api/utils.js'?{url:new URL('./vendor/0ad/utils.js',import.meta.url).href,shortCircuit:true}:n(s,c);}});
