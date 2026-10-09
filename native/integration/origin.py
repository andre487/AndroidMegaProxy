"""HTTP origin reachable only inside the disposable Docker network."""

import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Echo(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if self.path != "/echo" or not 0 < length <= 1024 * 1024:
            self.send_error(400)
            return
        payload = self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("X-MegaProxy-Origin", "integration")
        self.end_headers()
        self.wfile.write(payload)


def udp_echo():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind(("0.0.0.0", 8081))
        while True:
            payload, address = sock.recvfrom(65535)
            sock.sendto(payload, address)


threading.Thread(target=udp_echo, daemon=True).start()
ThreadingHTTPServer(("0.0.0.0", 8080), Echo).serve_forever()
