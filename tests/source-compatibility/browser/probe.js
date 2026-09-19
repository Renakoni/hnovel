/* Run only on the local fixture. No website credentials or content are collected. */
(async function () {
    const identity = nav => ({
        userAgent: nav.userAgent, webdriver: nav.webdriver, platform: nav.platform,
        languages: Array.from(nav.languages || []), hardwareConcurrency: nav.hardwareConcurrency,
        maxTouchPoints: nav.maxTouchPoints, userAgentData: nav.userAgentData?.toJSON() || null
    });
    const result = { main: identity(navigator), secureContext: isSecureContext,
        fetchNative: /\[native code\]/.test(Function.prototype.toString.call(fetch)),
        xhrNative: /\[native code\]/.test(Function.prototype.toString.call(XMLHttpRequest)),
        cookieOwnProperty: Object.prototype.hasOwnProperty.call(document, 'cookie'),
        privilegedBridge: typeof window.SourceBrowser !== 'undefined' };
    const canvas = document.createElement('canvas');
    const gl = canvas.getContext('webgl');
    const debug = gl?.getExtension('WEBGL_debug_renderer_info');
    result.gpu = debug ? {vendor: gl.getParameter(debug.UNMASKED_VENDOR_WEBGL), renderer: gl.getParameter(debug.UNMASKED_RENDERER_WEBGL)} : null;
    const timeout = (promise, fallback) => Promise.race([promise, new Promise(resolve => setTimeout(() => resolve(fallback), 2000))]);
    result.frame = await timeout(new Promise(resolve => {
        const frame = document.createElement('iframe');
        frame.src = '/frame';
        frame.onload = () => { try { resolve({ identity: identity(frame.contentWindow.navigator), content: frame.contentDocument.body.textContent }); } catch (_) { resolve({error: 'inaccessible'}); } };
        document.body.appendChild(frame);
    }), {error: 'timeout'});
    result.worker = await timeout(new Promise(resolve => {
        try { const worker = new Worker('/worker.js'); worker.onmessage = e => { resolve(e.data); worker.terminate(); }; worker.onerror = () => resolve({error: 'failed'}); }
        catch (_) { resolve({error: 'blocked'}); }
    }), {error: 'timeout'});
    try { result.network = await (await fetch('/echo', {method: 'POST', body: 'fixture=value', headers: {'Content-Type': 'application/x-www-form-urlencoded'}})).json(); }
    catch (_) { result.network = {error: 'failed'}; }
    result.domCookies = document.cookie.split(';').map(value => value.trim()).filter(value => /^(visible|hidden)=fixture$/.test(value));
    window.probeResult = result;
    document.querySelector('pre').textContent = JSON.stringify(result);
    window.probeDone = true;
    await fetch('/result?client=' + encodeURIComponent(new URL(location.href).searchParams.get('client')), {method: 'POST', body: JSON.stringify(result)}).catch(() => {});
})();
