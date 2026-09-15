"""Loopback-only fixture for the explicitly opted-in Android Clash tests."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'<!doctype html><html><head><title>hnovel-route-fixture</title></head><body>hnovel-route-fixture<script>fetch("/child").then(r=>r.text()).then(t=>document.body.dataset.child=t)</script></body></html>'
        if self.path.startswith('/child'):
            body = b'hnovel-route-fixture-child'
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    for port in (18764, 18765):
        server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        Thread(target=server.serve_forever, daemon=False).start()
        print(f'fixture ready on {port}', flush=True)
