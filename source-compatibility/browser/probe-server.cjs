const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const results = Object.create(null);
const port = Number(process.argv[2] || 18766);
http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let body = '';
    for await (const chunk of req) { body += chunk; if (body.length > 32768) { res.writeHead(413).end(); return; } }
    if (url.pathname === '/probe') {
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.setHeader('Set-Cookie', ['visible=fixture; Path=/', 'hidden=fixture; Path=/; HttpOnly']);
        res.end('<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"><title>Browser fixture</title></head><body><pre>Waiting</pre><script src="/probe.js"></script></body></html>');
    } else if (url.pathname === '/probe.js') {
        res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync(path.join(__dirname, 'probe.js')));
    } else if (url.pathname === '/frame') {
        res.setHeader('Content-Type', 'text/html'); res.end('<p>native-frame</p>');
    } else if (url.pathname === '/worker.js') {
        res.setHeader('Content-Type', 'application/javascript');
        res.end('postMessage({userAgent:navigator.userAgent,platform:navigator.platform,hardwareConcurrency:navigator.hardwareConcurrency});');
    } else if (url.pathname === '/echo') {
        res.setHeader('Content-Type', 'application/json');
        const cookies = (req.headers.cookie || '').split(';').map(value => value.trim()).filter(value => /^(visible|hidden)=fixture$/.test(value));
        res.end(JSON.stringify({method:req.method, body, userAgent:req.headers['user-agent'], cookies, secChUa:req.headers['sec-ch-ua'] || null}));
    } else if (url.pathname === '/result' && req.method === 'POST') {
        try { results[url.searchParams.get('client')] = JSON.parse(body); res.end('saved'); } catch (_) { res.writeHead(400).end(); }
    } else if (url.pathname === '/results') {
        res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(results));
    } else if (url.pathname === '/source.json') {
        res.setHeader('Content-Type', 'application/json');
        const realUa = url.searchParams.get('nativeUa') === '1';
        res.end(JSON.stringify({bookSourceUrl:`http://127.0.0.1:${port}`,bookSourceName:'Local browser environment probe',bookSourceType:0,enabled:true,enabledExplore:false,
            header: realUa ? "@js:JSON.stringify({'User-Agent':java.getWebViewUA()})" : '',
            searchUrl:'/probe?client=' + (realUa ? 'md3-webview-ua' : 'md3') + ',{"webView":true,"webJs":"window.probeDone ? document.documentElement.outerHTML : null"}',
            ruleSearch:{bookList:'body',name:'@js:"Browser fixture"',bookUrl:'@js:baseUrl'}}));
    } else { res.writeHead(404).end(); }
}).listen(port, '127.0.0.1', () => console.log(`Local browser probe: http://127.0.0.1:${port}/probe`));
