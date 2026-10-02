"""HTTP origin reachable only inside the disposable Docker network."""

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


ThreadingHTTPServer(("0.0.0.0", 8080), Echo).serve_forever()
