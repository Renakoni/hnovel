const {spawn,execFileSync}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path');
const [adb,serial,client,output]=process.argv.slice(2);
if(serial!=='127.0.0.1:16416')throw Error('Expected research emulator');
if(!/^[a-z0-9-]+$/.test(client))throw Error('Expected a neutral client label');
const command=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',windowsHide:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const report=await(await fetch('http://127.0.0.1:18770')).json(),port=new URL(report.url).port;
 command('reverse',`tcp:${port}`,`tcp:${port}`);
 const args=['-s',serial,'shell','am','instrument','-w','-e','class',
  'indi.dmzz_yyhyy.lightnovelreader.sourceexecution.NativeBrowserInstrumentedTest#recordLocalEnvironment',
  '-e','browserProbeUrl',`http://127.0.0.1:18767/consistency?transport=${client}`,'-e','nativeProbe','true'];
 if(client.includes('foreground'))args.push('-e','foregroundProbe','true');
 args.push('indi.dmzz_yyhyy.lightnovelreader.debug.test/androidx.test.runner.AndroidJUnitRunner');
 const child=spawn(adb,args,{windowsHide:true}),log=fs.createWriteStream(output);
 child.stdout.pipe(log);child.stderr.pipe(log);
 const done=new Promise(resolve=>child.on('close',resolve));
 let observed=false;
 for(let attempt=0;attempt<35;attempt++){
  await delay(500);
  try{
   const pid=command('shell','pidof','indi.dmzz_yyhyy.lightnovelreader.debug:source_browser_native').trim();
   if(!/^\d+$/.test(pid))continue;
   command('forward','tcp:19229',`localabstract:webview_devtools_remote_${pid}`);
   const pages=await(await fetch('http://127.0.0.1:19229/json')).json();
   if(!pages.some(p=>p.url.includes('transport='+client)))continue;
   // Wait for all neutral realms, before releasing extraction through CDP.
   await delay(7000);
   const measured=execFileSync(process.execPath,[path.join(__dirname,'transport-client.cjs'),'19229',client],{encoding:'utf8',windowsHide:true});
   console.log(measured.trim());observed=true;break;
  }catch(e){if(attempt===34)throw e;}
 }
 if(!observed)throw Error('No owned transport fixture was sampled');
 await done;
 const measured=await(await fetch('http://127.0.0.1:18770')).json();
 fs.writeFileSync(output+'.json',JSON.stringify(measured,null,2));
 console.log(JSON.stringify({hellos:measured.hellos.length,connections:measured.connections.length}));
})().catch(e=>{console.error(e.message);process.exitCode=1});
