// Owned loopback fixture only. Each instrumentation run starts a fresh browser process/profile.
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const [adb,device,apk,output]=process.argv.slice(2);
if(!adb || device!=='127.0.0.1:16416' || !apk || !output) throw Error('Usage: node run-stability.cjs ADB 127.0.0.1:16416 APK OUTPUT');
const run=(args)=>{const result=spawnSync(adb,['-s',device,...args],{encoding:'utf8',timeout:120000,windowsHide:true});
    if(result.error || result.status!==0) throw Error(result.error?.message || 'ADB failed');return result.stdout;};
const report={schema:1,startedUtc:new Date().toISOString(),device,
    apkSha256:crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex'),
    probeSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname,'realm-probe.js'))).digest('hex'),
    provider:run(['shell','dumpsys','webviewupdate']).match(/Current WebView package \(name, version\): (.*)/)?.[1],
    method:'Three fresh instrumentation/browser launches per mode; three audio/font reads per realm. No external website or CAPTCHA.',
    samples:{},failures:[]};
for(const mode of ['background','foreground']) for(let index=1;index<=3;index++) {
    const key=mode+'-'+index;
    try {
        const log=run(['shell','am','instrument','-w','-e','class',
            'indi.renakoni.nextvol.sourceexecution.NativeBrowserInstrumentedTest#recordLocalEnvironment',
            '-e','browserProbeUrl','http://127.0.0.1:18767/consistency?client='+key,
            '-e','nativeProbe','true','-e','foregroundProbe',String(mode==='foreground'),
            'indi.renakoni.nextvol.debug.test/androidx.test.runner.AndroidJUnitRunner']);
        fs.writeFileSync(output+'.'+key+'.log',log);
        const raw=log.match(/INSTRUMENTATION_STATUS: browserProbe=(.*)/)?.[1];
        if(!/OK \(1 test\)/.test(log) || !raw) throw Error('Instrumentation did not complete its probe');
        report.samples[key]=JSON.parse(raw);
        console.log(key+': captured');
    } catch(e) {report.failures.push({sample:key,message:e.message});console.log(key+': failed');}
    fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
}
report.finishedUtc=new Date().toISOString();
report.limits=['Fresh fixture profiles do not prove existing account cookie persistence.',
    'Media canPlayType is capability reporting, not decode/playback proof; font requests may use fallback.',
    'No physical Android, newer provider, external routing, STUN/TURN, QUIC or CF frequency measurement.'];
fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
if(report.failures.length) process.exitCode=1;
