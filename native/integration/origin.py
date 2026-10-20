"""HTTP origin reachable only inside the disposable Docker network."""

import socket
import ssl
import struct
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Echo(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if self.path == "/dns-query":
            payload = self.rfile.read(length)
            response = dns_reply(payload)
            self.send_response(200)
            self.send_header("Content-Type", "application/dns-message")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)
            return
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


def dns_reply(query):
    # Controlled A record; preserve the question and transaction ID.
    return (
        query[:2]
        + b"\x81\x80\x00\x01\x00\x01\x00\x00\x00\x00"
        + query[12:]
        + b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\xcb\x00\x71\x2a"
    )


def dns_udp():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind(("0.0.0.0", 10053))
        while True:
            payload, address = sock.recvfrom(65535)
            sock.sendto(dns_reply(payload), address)


def dns_tcp():
    def serve(conn):
        with conn, conn.makefile("rb") as stream:
            header = stream.read(2)
            if len(header) == 2:
                reply = dns_reply(stream.read(struct.unpack("!H", header)[0]))
                conn.sendall(struct.pack("!H", len(reply)) + reply)

    with socket.create_server(("0.0.0.0", 10053)) as server:
        while True:
            conn, _ = server.accept()
            threading.Thread(target=serve, args=(conn,), daemon=True).start()


def doh():
    with tempfile.TemporaryDirectory() as directory:
        cert, key = directory + "/cert.pem", directory + "/key.pem"
        subprocess.run(
            [
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-days",
                "1",
                "-subj",
                "/CN=disposable-dns-fixture",
                "-keyout",
                key,
                "-out",
                cert,
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        server = ThreadingHTTPServer(("0.0.0.0", 8444), Echo)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        server.serve_forever()


for target in (udp_echo, dns_udp, dns_tcp, doh):
    threading.Thread(target=target, daemon=True).start()
ThreadingHTTPServer(("0.0.0.0", 8080), Echo).serve_forever()
