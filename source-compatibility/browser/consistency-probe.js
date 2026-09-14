(async function () {
    await new Promise(resolve=>setTimeout(resolve,500));
    const bounded = (promise, label) => Promise.race([promise,new Promise(resolve=>setTimeout(()=>resolve({error:label+' timeout'}),5000))]);
    const result={schema:1,main:await collectBrowserRealm(),frames:{},workers:{}};
    async function frame(origin, key) {
        const element=document.createElement('iframe');element.width='240';element.height='160';
        const ready=new Promise(resolve=>{
            const listener=event=>{if(event.source===element.contentWindow && event.data?.fixture===key){removeEventListener('message',listener);resolve(event.data.result)}};
            addEventListener('message',listener);
        });
        element.src=origin+'/realm-frame?key='+key;document.body.append(element);
        return bounded(ready,key);
    }
    result.frames.sameOrigin=await frame(location.origin,'same');
    result.frames.crossOrigin=await frame('http://127.0.0.1:18768','cross');
    result.workers.dedicated=await bounded(new Promise(resolve=>{
        const worker=new Worker('/realm-worker');worker.onmessage=e=>{resolve(e.data);worker.terminate()};worker.onerror=()=>resolve({error:'worker error'});
    }),'dedicated');
    result.workers.shared=typeof SharedWorker==='undefined' ? {unsupported:true} : await bounded(new Promise(resolve=>{
        const worker=new SharedWorker('/realm-shared');worker.port.onmessage=e=>{resolve(e.data);worker.port.close()};worker.port.start();
    }),'shared');
    result.network=await(await fetch('/echo')).json();
    result.observation='Background and foreground run separately; provider/version is recorded by the Android host.';
    window.consistencyResult=result;
    document.querySelector('pre').textContent=JSON.stringify(result);
    await fetch('/result?client='+encodeURIComponent(new URL(location.href).searchParams.get('client')),{method:'POST',body:JSON.stringify(result)});
    // Explicit neutral transport run keeps this test-only document alive for CDP.
    window.consistencyDone=!new URL(location.href).searchParams.has('transport');
})();
