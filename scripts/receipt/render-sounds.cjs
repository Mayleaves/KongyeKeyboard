// Render the supplied second HTML's unmodified Web Audio engine to small offline PCM assets.
// node --experimental-websocket scripts/receipt/render-sounds.cjs
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const profile = fs.mkdtempSync(path.join(root, 'build/receipt-audio-'));
const chrome = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', '--no-first-run', '--disable-background-networking', '--remote-debugging-port=0',
  '--user-data-dir='+profile, 'about:blank'
], {windowsHide:true, stdio:'ignore'});
const pause = ms => new Promise(r=>setTimeout(r,ms));
let socket;
async function main() {
  const portFile = path.join(profile,'DevToolsActivePort');
  for(let n=0;n<100&&!fs.existsSync(portFile);n++)await pause(100);
  const port = fs.readFileSync(portFile,'utf8').split('\n')[0];
  const [target] = await fetch('http://127.0.0.1:'+port+'/json/list').then(r=>r.json());
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r,j)=>{socket.addEventListener('open',r,{once:true});socket.addEventListener('error',j,{once:true})});
  let id=0; const pending = new Map();
  socket.addEventListener('message',e=>{
    const data=JSON.parse(e.data), p=pending.get(data.id);if(!p)return;
    pending.delete(data.id);data.error?p.reject(data.error):p.resolve(data.result);
  });
  const source=fs.readFileSync(path.join(__dirname,'sound-reference.js'),'utf8');
  const expression=`(async()=>{${source}
    const result={};
    for(const [name,method,arg,seconds] of [
      ['key','playKeyClick',0,.06], ['step_slow','playStepperTick',.7,.04],
      ['step_fast','playStepperTick',1.15,.04], ['stamp','playStampThud',0,.15],
      ['tear','playPaperTear',0,.26]]) {
      const ctx=new OfflineAudioContext(1,Math.ceil(44100*seconds),44100);
      SoundFX.ctx=ctx;SoundFX.lastStepTime=-Infinity;SoundFX.ensure=()=>ctx;
      // Offline contexts are suspended until startRendering; node creation is otherwise identical.
      Object.defineProperty(ctx,'state',{value:'running'});
      SoundFX[method](arg);
      const rendered=await ctx.startRendering();
      const bytes=new Uint8Array(rendered.length*2), view=new DataView(bytes.buffer);
      rendered.getChannelData(0).forEach((v,i)=>view.setInt16(i*2,Math.round(Math.max(-1,Math.min(1,v))*32767),true));
      let binary='';for(const b of bytes)binary+=String.fromCharCode(b);
      result[name]=btoa(binary);
    } return result;
  })()`;
  const result=await new Promise((resolve,reject)=>{
    pending.set(++id,{resolve,reject});socket.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));
  });
  if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));
  const dest=path.join(root,'app/src/main/res/raw');fs.mkdirSync(dest,{recursive:true});
  for(const [name,data] of Object.entries(result.result.value)) {
    const pcm=Buffer.from(data,'base64'),h=Buffer.alloc(44);
    h.write('RIFF');h.writeUInt32LE(36+pcm.length,4);h.write('WAVEfmt ',8);h.writeUInt32LE(16,16);
    h.writeUInt16LE(1,20);h.writeUInt16LE(1,22);h.writeUInt32LE(44100,24);h.writeUInt32LE(88200,28);
    h.writeUInt16LE(2,32);h.writeUInt16LE(16,34);h.write('data',36);h.writeUInt32LE(pcm.length,40);
    fs.writeFileSync(path.join(dest,'receipt_'+name+'.wav'),Buffer.concat([h,pcm]));
    console.log(name+': '+pcm.length+' PCM bytes');
  }
}
main().catch(e=>{console.error(e);process.exitCode=1}).finally(()=>{socket?.close();chrome.kill()});
