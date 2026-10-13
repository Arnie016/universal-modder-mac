// SPDX-License-Identifier: GPL-2.0-or-later
import {formationDestinations} from './simulation.js';
export function selectedGuardians(w,selected=[]){const alive=w.units.filter(u=>u.team===1&&u.hp>0).map(u=>u.id);return selected.length?selected.filter(id=>alive.includes(id)):alive;}
export function chooseGuardian(w,selected,id,additive=false){if(!w.units.some(u=>u.id===id&&u.team===1&&u.hp>0))return [...selected];if(!additive)return [id];if(selected.includes(id))return selected.length>1?selected.filter(n=>n!==id):[id];return [...selected,id];}
export function previewMove(w,selected,x,z){const ids=selectedGuardians(w,selected);if(!ids.length)return {valid:false,ids,destinations:[],message:'The selected guardians have fallen. Choose living guardians.'};try{return {valid:true,ids,destinations:formationDestinations(w,ids.length,x,z),message:''};}catch(e){return {valid:false,ids,destinations:[],message:e.message};}}
