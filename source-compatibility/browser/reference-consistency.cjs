// Import one neutral fixture into the reference app and disable it after sampling.
const origin='http://127.0.0.1:18767';
const source={bookSourceUrl:origin,bookSourceName:'Local consistency fixture',bookSourceType:0,enabled:true,enabledExplore:false,
 header:"@js:JSON.stringify({'User-Agent':java.getWebViewUA()})",
 searchUrl:'/consistency?client=md3-background,{"webView":true,"webJs":"window.consistencyDone ? document.documentElement.outerHTML : null"}',
 ruleSearch:{bookList:'body',name:'@js:"Browser fixture"',bookUrl:'@js:baseUrl'}};
async function save(enabled){const r=await(await fetch('http://127.0.0.1:18122/saveBookSource',{
 method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...source,enabled})})).json();
 if(!r.isSuccess)throw Error(r.errorMsg || 'reference import failed');}
(async()=>{try{await save(true);await new Promise((resolve,reject)=>{
 const ws=new WebSocket('ws://127.0.0.1:18123/bookSourceDebug');
 const timer=setTimeout(()=>{ws.close();reject(Error('reference probe timeout'));},60000);
 ws.onopen=()=>ws.send(JSON.stringify({tag:origin,key:'fixture'}));
 ws.onmessage=e=>{const msg=String(e.data);if(msg.includes('Exception'))console.log(msg.slice(0,250));};
 ws.onclose=()=>{clearTimeout(timer);resolve()};ws.onerror=()=>{clearTimeout(timer);reject(Error('reference socket error'))};
 });}finally{await save(false);console.log('Neutral reference source disabled');}})().catch(e=>{console.error(e.message);process.exitCode=1});
