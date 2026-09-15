(async function () {
    const args = new URL(location.href).searchParams;
    const previous = {cookie: document.cookie, value: localStorage.getItem('preference')};
    if (args.has('set')) {
        const value = args.get('set');
        localStorage.setItem('preference', value);
        document.cookie = 'persistent=' + value + '; Max-Age=3600; Path=/; SameSite=Lax';
        document.cookie = 'sessionOnly=' + value + '; Path=/; SameSite=Lax';
    }
    try {
        if (args.has('dns')) {
            try {
                const text = await fetch('https://vpn-only.hnovel.test:18765/fetch').then(response => response.text());
                window.answer = JSON.stringify({dns: text === 'fetch'});
            } catch (failure) {
                window.answer = JSON.stringify({dns: false});
            }
            return;
        }
        const registration = await navigator.serviceWorker.register('/sw.js');
        await navigator.serviceWorker.ready;
        if (!navigator.serviceWorker.controller) await new Promise(resolve => {
            navigator.serviceWorker.addEventListener('controllerchange', resolve, {once: true});
        });
        if (args.has('pulse')) {
            const started = new Promise(resolve => navigator.serviceWorker.addEventListener('message', resolve, {once: true}));
            registration.active.postMessage('pulse');
            await started;
            window.answer = JSON.stringify({pulse: true});
            return;
        }
        const iframe = new Promise((resolve, reject) => {
            const frame = document.createElement('iframe');
            frame.src = '/frame';
            frame.onload = () => resolve(frame.contentDocument.body.textContent);
            frame.onerror = reject;
            document.body.appendChild(frame);
        });
        const xhr = new Promise((resolve, reject) => {
            const request = new XMLHttpRequest();
            request.open('GET', '/xhr');
            request.onload = () => resolve(request.responseText);
            request.onerror = reject;
            request.send();
        });
        const worker = new Promise((resolve, reject) => {
            const worker = new Worker('/worker.js');
            worker.onmessage = event => { worker.terminate(); resolve(event.data); };
            worker.onerror = event => { worker.terminate(); reject(event); };
        });
        const children = await Promise.all([fetch('/fetch').then(r => r.text()), xhr, iframe, worker,
            fetch('/sw-fetch').then(r => r.text())]);
        window.answer = JSON.stringify({previous, value: localStorage.getItem('preference'), cookie: document.cookie, children});
    } catch (failure) {
        window.answer = JSON.stringify({error: String(failure)});
    }
}());
