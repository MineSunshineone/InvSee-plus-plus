'use strict'
// Actual network clients, older protocol translated by official ViaBackwards.
// Run only against the disposable localhost server. Never takes credentials.
const mineflayer = require('mineflayer')
const fs = require('fs')
const readline = require('readline')
const host = process.env.MC_HOST || '127.0.0.1'
if (!['127.0.0.1', 'localhost', '::1'].includes(host)) throw Error('Refusing non-loopback server')
const names = ['InvTarget', 'InvViewerA', 'InvViewerB']
const bots = []
const log = (type, data) => console.log(JSON.stringify({ time: new Date().toISOString(), type, ...data }))
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
function waitFor(test, ms = 20000) { return new Promise((resolve, reject) => {
  const start = Date.now(); const timer = setInterval(() => {
    try { const value = test(); if (value) { clearInterval(timer); resolve(value) }
      else if (Date.now()-start > ms) { clearInterval(timer); reject(Error('condition timeout')) }
    } catch (e) { clearInterval(timer); reject(e) }
  }, 50)
}) }
function count(bot) { return bot.inventory.items().filter(x => x.name === 'diamond').reduce((a,x) => a+x.count,0) }
function counts() { return bots.map(bot => ({ player: bot.username, diamonds: count(bot), cursor: bot.currentWindow?.selectedItem?.name || null })) }
async function connect(name) {
  const bot = mineflayer.createBot({ host, port: Number(process.env.MC_PORT || 25572), username: name, version: '26.1', auth: 'offline', hideErrors: false })
  bot.on('error', error => log('client-error', { player:name, error:String(error) }))
  bot.on('kicked', reason => log('kicked', { player:name, reason }))
  bot.on('end', reason => log('end', { player:name, reason }))
  bot.on('messagestr', message => log('chat', { player:name, message }))
  bot.on('windowOpen', window => log('window-open', { player:name, id:window.id, title:window.title, slots:window.slots.length, start:window.inventoryStart }))
  await new Promise((resolve,reject) => { const t=setTimeout(()=>reject(Error('spawn timeout '+name)),30000); bot.once('spawn',()=>{ clearTimeout(t); resolve() }); bot.once('error',e=>{clearTimeout(t);reject(e)}); bot.once('kicked',r=>{clearTimeout(t);reject(Error('kicked: '+JSON.stringify(r)))}) })
  bot.physicsEnabled = false
  return bot
}
async function inspect(bot, target='InvTarget') {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await delay(200); bot.chat('/invsee '+target)
  return await waitFor(()=>bot.currentWindow)
}
async function race() {
  const [,a,b]=bots
  await Promise.all([inspect(a),inspect(b)])
  await delay(500)
  if (count(bots[0]) !== 64 || count(a) || count(b)) throw Error('Require exactly64 diamonds on target and0 on viewers')
  const before = counts(); log('before-race',{counts:before})
  await Promise.all([a.clickWindow(0,0,0),b.clickWindow(0,0,0)])
  await delay(150)
  const start=performance.now()
  await Promise.all([a.clickWindow(81,0,0),b.clickWindow(81,0,0)])
  await waitFor(()=>count(a)+count(b)===64)
  await delay(1000)
  const after=counts(); if(after.reduce((sum,x)=>sum+x.diamonds,0)!==64) throw Error('conservation failed')
  if(count(a)!==64 && count(b)!==64) throw Error('not exactly one full-stack winner')
  log('PASS-client-observed-race',{counts:after,ms:performance.now()-start, caveat:'Verify server playerdata/journal and distinct-owner probe separately'})
}
async function main() {
  for (const name of names) { bots.push(await connect(name)); log('spawn',{player:name,uuid:bots.at(-1).uuid}); await delay(300) }
  log('ready',{commands:'status | race | inspect A/B [UUID] | chat T/A/B <command> | click A/B slot button mode | quit T/A/B | reconnect T/A/B | window A/B | exit'})
  const rl=readline.createInterface({input:process.stdin,terminal:false})
  for await (const line of rl) {
    const [cmd,who,...args]=line.trim().split(/\s+/); const bot=bots[{T:0,A:1,B:2}[who]]
    try {
      if(cmd==='status') log('status',{counts:counts(),positions:bots.map(b=>({name:b.username,position:b.entity?.position}))})
      else if(cmd==='race') await race()
      else if(cmd==='inspect') await inspect(bot,args[0])
      else if(cmd==='chat') bot.chat(args.join(' '))
      else if(cmd==='click') await bot.clickWindow(...args.map(Number))
      else if(cmd==='close') { if(bot.currentWindow)bot.closeWindow(bot.currentWindow) }
      else if(cmd==='window') log('window-snapshot',{player:bot.username,slots:bot.currentWindow?.slots.map((item,slot)=>item?{slot,name:item.name,count:item.count}:null).filter(Boolean),cursor:bot.currentWindow?.selectedItem})
      else if(cmd==='reconnect') { bots[{T:0,A:1,B:2}[who]]=await connect(names[{T:0,A:1,B:2}[who]]);log('reconnected',{player:who}) }
      else if(cmd==='quit') bot.quit('test disconnect')
      else if(cmd==='exit') break
    } catch(error) { log('scenario-error',{error:String(error)}); process.exitCode=1 }
  }
  for (const bot of bots) bot.quit('test complete')
  setTimeout(()=>process.exit(process.exitCode||0),250).unref()
}
main().catch(error=>{log('fatal',{error:String(error)});for(const bot of bots)bot.quit();process.exitCode=1})
