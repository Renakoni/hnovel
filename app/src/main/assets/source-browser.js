/* Native networking is blocked. The bridge carries bounded text requests to the source's HTTP broker. */
(function () {
    'use strict';
    function call(op, args) {
        var result = JSON.parse(SourceBrowser.call(op, JSON.stringify(args)));
        if (result === null) throw new Error('Source request failed');
        return result;
    }
    function absolute(url) { return new URL(String(url), location.href).href; }
    function persist() {
        var state = {};
        for (var i = 0; i < localStorage.length; i++) {
            var key = localStorage.key(i); state[key] = localStorage.getItem(key);
        }
        call('storage', {url: location.href, value: state});
    }
    var set = Storage.prototype.setItem, remove = Storage.prototype.removeItem, clear = Storage.prototype.clear;
    Storage.prototype.setItem = function (k, v) { set.call(this, k, v); if (this === localStorage) persist(); };
    Storage.prototype.removeItem = function (k) { remove.call(this, k); if (this === localStorage) persist(); };
    Storage.prototype.clear = function () { clear.call(this); if (this === localStorage) persist(); };
    addEventListener('pagehide', persist);
    Object.defineProperty(document, 'cookie', {configurable: false,
        get: function () { return call('cookie', {url: location.href}); },
        set: function (value) { call('cookie', {url: location.href, value: String(value)}); }
    });
    function send(url, options) {
        options = options || {};
        var headers = {}, input = new Headers(options.headers || {});
        input.forEach(function (v, k) { headers[k] = v; });
        if (options.signal && options.signal.aborted) throw new Error('Aborted');
        var body = options.body;
        if (body instanceof URLSearchParams) {
            body = body.toString();
            if (!input.has('content-type')) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
        }
        if (body != null && typeof body !== 'string') throw new Error('Unsupported request body');
        var result = call('request', {url: absolute(url), method: String(options.method || 'GET').toUpperCase(), headers: headers, body: body});
        if (options.signal && options.signal.aborted) throw new Error('Aborted');
        return result;
    }
    window.fetch = function (url, options) {
        return Promise.resolve().then(function () {
            if (url instanceof Request) throw new Error('Use a URL and explicit options');
            var r = send(url, options);
            var binary = atob(r.bytes), bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            var response = new Response(r.status === 204 || r.status === 205 || r.status === 304 ? null : bytes, {status: r.status, headers: r.headers});
            Object.defineProperty(response, 'url', {value: r.url});
            return response;
        });
    };
    function XHR() { this.readyState = 0; this.status = 0; this.responseText = ''; this.responseType = ''; this.listeners = {}; this.headers = {}; }
    XHR.prototype.open = function (method, url, async) {
        this.method = method; this.url = url; this.async = async !== false; this.readyState = 1; this.emit('readystatechange');
    };
    XHR.prototype.setRequestHeader = function (name, value) { this.headers[name] = value; };
    XHR.prototype.addEventListener = function (name, fn) { (this.listeners[name] || (this.listeners[name] = [])).push(fn); };
    XHR.prototype.emit = function (name) {
        var event = {type: name, target: this};
        if (this['on' + name]) this['on' + name](event);
        (this.listeners[name] || []).forEach(function (fn) { fn(event); });
    };
    XHR.prototype.getResponseHeader = function (key) { var headers = this.result ? this.result.headers : {}; return headers[Object.keys(headers).find(function (k) { return k.toLowerCase() === key.toLowerCase(); })] || null; };
    XHR.prototype.getAllResponseHeaders = function () { var h = this.result ? this.result.headers : {}; return Object.keys(h).map(function (k) { return k + ': ' + h[k]; }).join('\r\n'); };
    XHR.prototype.abort = function () { this.aborted = true; this.emit('abort'); };
    XHR.prototype.send = function (body) {
        var self = this;
        function run() {
            if (self.aborted) return;
            try {
                var result = send(self.url, {method: self.method, headers: self.headers, body: body});
                if (self.aborted) return;
                self.result = result; self.status = result.status; self.responseURL = result.url; self.responseText = result.body;
                self.response = self.responseType === 'json' ? JSON.parse(result.body) : result.body;
                self.readyState = 4; self.emit('readystatechange'); self.emit('load');
            } catch (e) { self.readyState = 4; self.emit('readystatechange'); self.emit('error'); }
            self.emit('loadend');
        }
        if (this.async) setTimeout(run, 0); else run();
    };
    window.XMLHttpRequest = XHR;
    function submit(form, submitter) {
        var url = absolute(form.action || location.href), method = (form.method || 'get').toUpperCase();
        var fields = new URLSearchParams();
        new FormData(form).forEach(function (value, key) { if (typeof value !== 'string') throw new Error('File upload unavailable'); fields.append(key, value); });
        if (submitter && submitter.name) fields.append(submitter.name, submitter.value);
        if (method === 'GET') { location.href = url + (url.indexOf('?') < 0 ? '?' : '&') + fields.toString(); return; }
        call('navigate', {url: url, method: method, body: fields.toString(), headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'}});
    }
    addEventListener('submit', function (event) { event.preventDefault(); submit(event.target, event.submitter); }, true);
    HTMLFormElement.prototype.submit = function () { submit(this); };
})();
