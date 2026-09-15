"""Owned HTTPS fixture for emulator-only native route tests. No public-site traffic."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import ssl
from threading import Thread

ROOT = Path(__file__).resolve().parent


class Server(ThreadingHTTPServer):
    # Windows SO_REUSEADDR can hide a stale HTTP fixture listening on the same port.
    allow_reuse_address = False

    def get_request(self):
        try:
            return super().get_request()
        except OSError as error:
            print(f'Fixture TLS handshake failed: {error}', flush=True)
            raise


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/redirect':
            self.send_response(302)
            self.send_header('Location', '/')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        assets = {'/': 'index.html', '/page.js': 'page.js', '/worker.js': 'worker.js', '/sw.js': 'sw.js'}
        body = (ROOT / 'site' / assets[path]).read_bytes() if path in assets else path.lstrip('/').encode()
        kind = 'application/javascript' if path.endswith('.js') else 'text/html' if path in ('/', '/frame') else 'text/plain'
        self.send_response(200)
        self.send_header('Content-Type', kind)
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(ROOT / 'server.pem', ROOT / 'server-key.pem')
    servers = []
    for port in (18764, 18765):
        server = Server(('127.0.0.1', port), Handler)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        servers.append(server)
    threads = []
    for server in servers:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        threads.append(thread)
        print(f'Owned native TLS fixture ready on {server.server_port}', flush=True)
    try:
        for thread in threads:
            thread.join()
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
