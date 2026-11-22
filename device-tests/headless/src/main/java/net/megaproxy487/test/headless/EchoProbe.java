package net.megaproxy487.test.headless;

import android.app.Instrumentation;
import android.os.Bundle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;

/** Runs as the headless app's UID against the private integration origin. */
public class EchoProbe extends Instrumentation {
    private String host;

    @Override public void onCreate(Bundle args) {
        host = args.getString("host");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String token = UUID.randomUUID().toString();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 8080), 3000);
            socket.setSoTimeout(3000);
            String request = "POST /echo HTTP/1.0\r\nHost: fixture\r\nContent-Length: "
                    + token.length() + "\r\n\r\n" + token;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            try (Scanner scanner = new Scanner(socket.getInputStream(), "US-ASCII").useDelimiter("\\A")) {
                String response = scanner.hasNext() ? scanner.next() : "";
                result.putString("echo", response.startsWith("HTTP/1.0 200") &&
                        response.endsWith("\r\n\r\n" + token) ? "ok" : "invalid");
            }
        } catch (IOException e) {
            result.putString("echo", "unreachable");
        }
        finish(0, result);
    }
}
