'use strict'
// Read-only durable player data evidence; missing saves are errors, not zeros.
const nbt=require('prismarine-nbt'),fs=require('fs'),path=require('path')
const dir=process.argv[2]
if(!dir)throw Error('Usage: node snapshot-durable.cjs <isolated-world/players/data>')
const players={InvTarget:'223af85f-c96b-337e-8923-1db8ed0d947b',InvViewerA:'c58ecae9-b027-3319-b076-96bb393a37de',InvViewerB:'6fd8aa20-f8b3-3267-9d40-90923963a98d'}
;(async()=>{let total=0;for(const [name,id]of Object.entries(players)){const{parsed}=await nbt.parse(fs.readFileSync(path.join(dir,id+'.dat')));const value=nbt.simplify(parsed);const items=value.Inventory||[];const diamonds=items.filter(i=>i.id==='minecraft:diamond').reduce((n,i)=>n+i.count,0);total+=diamonds;console.log(JSON.stringify({name,id,diamonds,items,ender:value.EnderItems||[]}))}console.log(JSON.stringify({totalDiamonds:total,scope:'saved main inventories only, excludes cursor/ground/journal escrow'}))})().catch(e=>{console.error(e);process.exitCode=1})
