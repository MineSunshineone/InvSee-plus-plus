'use strict'
const nbt=require('prismarine-nbt'),fs=require('fs'),path=require('path'),assert=require('assert/strict')
const [dir,source,target,kind='main']=process.argv.slice(2)
async function read(id){const {parsed}=await nbt.parse(fs.readFileSync(path.join(dir,id+'.dat')));return nbt.simplify(parsed)}
;(async()=>{const [s,t]=await Promise.all([read(source),read(target)]);const keys=kind==='ender'?['EnderItems']:['Inventory','equipment'];for(const key of keys){const normalize=value=>Array.isArray(value)?[...value].sort((a,b)=>a.Slot-b.Slot):(value||{});assert.deepStrictEqual(normalize(t[key]),normalize(s[key]),key+' clone mismatch')}console.log(JSON.stringify({result:'PASS-exact-copy',source,target,kind,keys,scope:'NBT item components+equipment, not unrelated player fields'}))})().catch(e=>{console.error(e);process.exitCode=1})
