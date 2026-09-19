/* Neutral fixture only. Inspired by Chromix's cross-realm/capability acceptance approach. */
globalThis.collectBrowserRealm = async function () {
    const n = navigator;
    const result = {userAgent:n.userAgent, platform:n.platform, languages:Array.from(n.languages || []),
        hardwareConcurrency:n.hardwareConcurrency, deviceMemory:n.deviceMemory ?? null,
        maxTouchPoints:n.maxTouchPoints ?? null, webdriver:n.webdriver ?? null,
        userAgentData:n.userAgentData?.toJSON() || null,
        locale:Intl.DateTimeFormat().resolvedOptions(),
        fetchNative:/\[native code\]/.test(Function.prototype.toString.call(fetch)),
        secureContext:isSecureContext};
    if (n.userAgentData) result.highEntropy = await n.userAgentData.getHighEntropyValues(
        ['architecture','bitness','model','platformVersion','fullVersionList','wow64']);
    if (typeof document !== 'undefined') {
        result.geometry = {innerWidth,innerHeight,outerWidth,outerHeight,devicePixelRatio,
            screen:{width:screen.width,height:screen.height,availWidth:screen.availWidth,availHeight:screen.availHeight,colorDepth:screen.colorDepth},
            viewport:visualViewport ? {width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale} : null,
            visibility:document.visibilityState,hidden:document.hidden,focus:document.hasFocus()};
        const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(n),'webdriver');
        result.webdriverDescriptor = descriptor ? {configurable:descriptor.configurable,enumerable:descriptor.enumerable,
            nativeGetter:!!descriptor.get && /\[native code\]/.test(Function.prototype.toString.call(descriptor.get))} : null;
        result.cookieOwnProperty = Object.hasOwn(document,'cookie');
        result.privilegedBridge = typeof globalThis.SourceBrowser !== 'undefined';
    }
    const canvas = () => typeof document !== 'undefined' ? document.createElement('canvas') :
        typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(64,32) : null;
    const hash = bytes => { let value=2166136261; for (const byte of bytes) value=Math.imul(value ^ byte,16777619); return (value>>>0).toString(16); };
    try {
        const c=canvas(); c.width=64;c.height=32;
        const ctx=c.getContext('2d');ctx.fillStyle='#2468ac';ctx.fillRect(0,0,64,32);
        ctx.fillStyle='rgba(240,120,30,.5)';ctx.fillRect(5,5,20,10);
        const first=ctx.getImageData(0,0,64,32).data, second=ctx.getImageData(0,0,64,32).data;
        result.canvas={hash:hash(first),repeatEqual:hash(first)===hash(second),corner:Array.from(first.slice(0,4)),
            blend:Array.from(first.slice((6*64+6)*4,(6*64+6)*4+4)),
            outside:Array.from(ctx.getImageData(-1,-1,1,1).data)};
    } catch (e) { result.canvas={error:e.name}; }
    try {
        const c=canvas(); c.width=4;c.height=4;
        const gl=c.getContext('webgl',{preserveDrawingBuffer:true});
        if (!gl) result.webgl={available:false};
        else {
            const ext=gl.getExtension('WEBGL_debug_renderer_info'), pixel=new Uint8Array(4);
            gl.clearColor(.25,.5,.75,1);gl.clear(gl.COLOR_BUFFER_BIT);
            gl.readPixels(0,0,1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
            result.webgl={available:true,vendor:ext ? gl.getParameter(ext.UNMASKED_VENDOR_WEBGL) : null,
                renderer:ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : null,
                version:gl.getParameter(gl.VERSION),maxTextureSize:gl.getParameter(gl.MAX_TEXTURE_SIZE),
                attributes:gl.getContextAttributes(),pixel:Array.from(pixel),error:gl.getError()};
            gl.getExtension('WEBGL_lose_context')?.loseContext();
        }
    } catch (e) { result.webgl={error:e.name}; }
    if (n.gpu) {
        try { const adapter=await n.gpu.requestAdapter();result.webgpu=adapter ? {
            info:adapter.info ? {vendor:adapter.info.vendor,architecture:adapter.info.architecture,device:adapter.info.device,description:adapter.info.description} : null,
            features:Array.from(adapter.features),maxTextureDimension2D:adapter.limits.maxTextureDimension2D} : {available:false}; }
        catch(e){result.webgpu={error:e.name};}
    } else result.webgpu={available:false};
    try { result.storage=n.storage?.estimate ? await n.storage.estimate() : null; }
    catch(e){result.storage={error:e.name};}
    // Compare executable backends and repeated reads. No persona overrides or microphone access.
    try {
        const Audio = globalThis.OfflineAudioContext || globalThis.webkitOfflineAudioContext;
        if (!Audio) result.audio={available:false};
        else {
            const renders=[];
            for(let run=0;run<3;run++) {
                const audio=new Audio(1,4096,44100), oscillator=audio.createOscillator();
                oscillator.type='triangle';oscillator.frequency.value=1000;
                oscillator.connect(audio.destination);oscillator.start();
                const buffer=await audio.startRendering(), samples=buffer.getChannelData(0);
                const copy=new Float32Array(samples.length);buffer.copyFromChannel(copy,0);
                renders.push({sampleRate:buffer.sampleRate,length:samples.length,
                    hash:hash(new Uint8Array(samples.buffer,samples.byteOffset,samples.byteLength)),
                    copyEqual:samples.every((value,index)=>value===copy[index]),
                    finite:samples.every(Number.isFinite),peak:Math.max(...samples.map(Math.abs))});
            }
            result.audio={available:true,renders,repeatEqual:renders.every(r=>r.hash===renders[0].hash)};
        }
    } catch(e) { result.audio={error:e.name}; }
    try {
        const ctx=canvas().getContext('2d'), text='Browser fixture Aa09 \u4e2d\u6587';
        const metrics={};
        for(const family of ['sans-serif','serif','monospace','Roboto','Noto Sans CJK SC']) {
            ctx.font='16px "'+family+'"';
            const reads=Array.from({length:3},()=>{const m=ctx.measureText(text);return {
                width:m.width,left:m.actualBoundingBoxLeft,right:m.actualBoundingBoxRight,
                ascent:m.actualBoundingBoxAscent,descent:m.actualBoundingBoxDescent};});
            metrics[family]={value:reads[0],repeatEqual:reads.every(r=>JSON.stringify(r)===JSON.stringify(reads[0]))};
        }
        result.fonts={metrics,meaning:'CSS family requests may fall back; these are metrics, not installed-font identities'};
    } catch(e) { result.fonts={error:e.name}; }
    result.media={rtc:typeof RTCPeerConnection!=='undefined',mediaDevices:!!n.mediaDevices};
    if(typeof document!=='undefined') {
        const video=document.createElement('video'), audio=document.createElement('audio');
        result.media.canPlay={h264:video.canPlayType('video/mp4; codecs="avc1.42E01E"'),
            vp9:video.canPlayType('video/webm; codecs="vp9"'),aac:audio.canPlayType('audio/mp4; codecs="mp4a.40.2"'),
            opus:audio.canPlayType('audio/webm; codecs="opus"')};
    }
    return result;
};
