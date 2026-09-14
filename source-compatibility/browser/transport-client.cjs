// Only use with the opt-in, isolated /consistency?transport fixture account.
// The exception is reset before finishing the page; no production TLS code changes.
const [port,client]=process.argv.slice(2);
(async()=>{
 const report=await(await fetch('http://127.0.0.1:18770')).json();
 const targets=await(await fetch(`http://127.0.0.1:${port}/json`)).json();
 const target=targets.find(t=>t.url.startsWith('http://127.0.0.1:18767/consistency?')&&t.url.includes('transport='));
 if(!target)throw Error('Owned transport fixture target missing');
 const ws=new WebSocket(target.webSocketDebuggerUrl),pending=new Map(),failures=[];let id=0;
 ws.onmessage=e=>{const r=JSON.parse(e.data);if(r.method==='Network.loadingFailed')failures.push(r.params.errorText);if(r.id){const p=pending.get(r.id);pending.delete(r.id);r.error?p.reject(Error(r.error.message)):p.resolve(r.result)}};
 await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject});
 const call=(method,params={})=>new Promise((resolve,reject)=>{pending.set(++id,{resolve,reject});ws.send(JSON.stringify({id,method,params}))});
 try{
  await call('Network.enable');
  await call('Security.setIgnoreCertificateErrors',{ignore:true});
  const expression=`fetch(${JSON.stringify(report.url+'/echo?client='+encodeURIComponent(client))},{mode:'no-cors',cache:'no-store'}).then(()=>true).catch(e=>e.name)`;
  const result=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});
  console.log(JSON.stringify({client,fixtureTransportFetch:result.result?.value,failures,trustException:'neutral isolated profile only'}));
 }finally{
  await call('Security.setIgnoreCertificateErrors',{ignore:false});
  await call('Runtime.evaluate',{expression:'window.consistencyDone=true'});
  ws.close();
 }
})().catch(e=>{console.error(e.message);process.exitCode=1});
