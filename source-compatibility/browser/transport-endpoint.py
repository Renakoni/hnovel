"""Owned loopback evidence; reuses the pinned desktop reference's wire parser only.

Requires cryptography and h2. No browser launch or persona configuration is imported
into the Android app. Use --chromix with the read-only reference checkout.
"""
import argparse
import json
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--chromix', required=True, type=Path)
parser.add_argument('--dependencies', type=Path)
parser.add_argument('--control-port', type=int, default=18770)
args = parser.parse_args()
sys.dont_write_bytecode = True
if args.dependencies:
    sys.path.insert(0, str(args.dependencies))
sys.path.insert(0, str(args.chromix / 'tools'))
from fingerprint_transport_audit import endpoint

with tempfile.TemporaryDirectory(prefix='hnovel-owned-tls-') as directory:
    with endpoint(Path(directory)) as (server, url):
        class Control(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                with server.lock:
                    data = json.dumps({'url': url, 'trust': 'ephemeral self-signed fixture; production rejects it',
                        'certificate': (Path(directory) / 'cert.pem').read_text(),
                        'scope': 'full TLS handshakes and HTTP2; no QUIC, external routing or resumption proof',
                        'connections': server.connections, 'hellos': server.hellos,
                        'handshakeErrors': server.handshake_errors}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(data)

        print(json.dumps({'url': url, 'control': f'http://127.0.0.1:{args.control_port}'}), flush=True)
        class ControlServer(HTTPServer):
            allow_reuse_address = False
        ControlServer(('127.0.0.1', args.control_port), Control).serve_forever()
