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
  const originalWrite=bot._client.write.bind(bot._client)
  bot._client.write=(packetName,packet)=>{
    if(process.env.MC_STATIC_CLIENTS==='true' && ['position','look','position_look','flying'].includes(packetName)) {
      if(!bot._movementSuppressed){log('stationary-client-movement-suppressed',{player:name,packetName,scope:'Inventory-only translated-client tests; server checks unchanged'});bot._movementSuppressed=true}
      return
    }
    if(packetName==='window_click')bot._lastClick=packet
    return originalWrite(packetName,packet)
  }
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
  const source=a.currentWindow.slots.slice(0,54).findIndex(item=>item?.name==='diamond')
  if(source<0 || b.currentWindow.slots[source]?.name!=='diamond')throw Error('No matching diamond source snapshot')
  await Promise.all([a.clickWindow(source,0,0),b.clickWindow(source,0,0)])
  await delay(150)
  const start=performance.now()
  await Promise.all([a.clickWindow(81,0,0),b.clickWindow(81,0,0)])
  await waitFor(()=>count(a)+count(b)===64)
  await delay(1000)
  const after=counts(); if(after.reduce((sum,x)=>sum+x.diamonds,0)!==64) throw Error('conservation failed')
  if(count(a)!==64 && count(b)!==64) throw Error('not exactly one full-stack winner')
  log('PASS-client-observed-race',{counts:after,ms:performance.now()-start, caveat:'Verify server playerdata/journal and distinct-owner probe separately'})
}
async function admin(bot,command,requireCompletion=true){
 const result=await new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>{bot.removeListener('messagestr',received);reject(Error('admin response timeout: '+command))},15000)
  const received=message=>{if(/^(GIVE|CLEAR|CLONE) [0-9a-f-]+:|^Command refused|^No permission|^Wait for your current|^Inventory administration is busy/.test(message)){clearTimeout(timer);bot.removeListener('messagestr',received);resolve(message)}}
  bot.on('messagestr',received);bot.chat(command)
 })
 log('admin-result',{player:bot.username,command,result})
 if(requireCompletion&&!result.endsWith(': COMPLETED'))throw Error('admin did not complete: '+result)
 await delay(250);return result
}

async function main() {
  for (const name of names) { bots.push(await connect(name)); log('spawn',{player:name,uuid:bots.at(-1).uuid}); await delay(300) }
  log('ready',{commands:'status | race | inspect A/B [UUID] | chat T/A/B <command> | click A/B slot button mode | quit T/A/B | reconnect T/A/B | window A/B | exit'})
  const rl=readline.createInterface({input:process.stdin,terminal:false})
  for await (const line of rl) {
    log('harness-command',{line:line.trim()})
    const [cmd,who,...args]=line.trim().split(/\s+/); const bot=bots[{T:0,A:1,B:2}[who]]
    try {
      if(cmd==='status') log('status',{counts:counts(),positions:bots.map(b=>({name:b.username,position:b.entity?.position}))})
      else if(cmd==='race') await race()
      else if(cmd==='inspect') await inspect(bot,args[0])
      else if(cmd==='admin') await admin(bot,args.join(' '))
      else if(cmd==='assert') {const expected=[who,...args].map(Number);await waitFor(()=>bots.every((b,i)=>count(b)===expected[i]));await delay(200);if(!bots.every((b,i)=>count(b)===expected[i]))throw Error('unstable count assertion');log('PASS-counts',{expected,counts:counts()})}
      else if(cmd==='adminrace') {const before=count(bots[0]);const results=await Promise.all([admin(bots[1],'/invgive InvTarget diamond 1',false),admin(bots[2],'/invgive InvTarget diamond 1',false)]);const added=results.filter(x=>x.endsWith(': COMPLETED')).length;await waitFor(()=>count(bots[0])===before+added);log('PASS-administrative-race',{before,added,after:count(bots[0]),results})}
      else if(cmd==='chat') bot.chat(args.join(' '))
      else if(cmd==='click') await bot.clickWindow(...args.map(Number))
      else if(cmd==='replay') {
        if(!bot._lastClick)throw Error('No captured window click');const saved=bot._lastClick;
        for(const [mode,mouseButton,slot]of [[0,0,0],[1,0,0],[2,1,0],[5,0,-999],[5,1,0],[5,2,-999],[4,1,0],[6,0,0]])bot._client.write('window_click',{...saved,mode,mouseButton,slot,changedSlots:[]});
        log('stale-click-replay',{player:bot.username,windowId:saved.windowId,packets:8})
      }
      else if(cmd==='close') { if(bot.currentWindow)bot.closeWindow(bot.currentWindow) }
      else if(cmd==='window') log('window-snapshot',{player:bot.username,slots:bot.currentWindow?.slots.map((item,slot)=>item?{slot,name:item.name,count:item.count}:null).filter(Boolean),cursor:bot.currentWindow?.selectedItem})
      else if(cmd==='expectblocked') {
        let blocked=false;try { const unexpected=await connect(names[{T:0,A:1,B:2}[who]]);unexpected.quit('unexpected admission') } catch(e) { if(!String(e).includes('inventory is being edited'))throw e;blocked=true }
        if(!blocked)throw Error('Expected login rejection but player admitted');log('PASS-login-fence',{player:who})
      }
      else if(cmd==='reconnect') { bots[{T:0,A:1,B:2}[who]]=await connect(names[{T:0,A:1,B:2}[who]]);log('reconnected',{player:who}) }
      else if(cmd==='quit') bot.quit('test disconnect')
      else if(cmd==='exit') break
    } catch(error) { log('scenario-error',{error:String(error)}); process.exitCode=1 }
  }
  for (const bot of bots) bot.quit('test complete')
  setTimeout(()=>process.exit(process.exitCode||0),250).unref()
}
main().catch(error=>{log('fatal',{error:String(error)});for(const bot of bots)bot.quit();process.exitCode=1})
