package com.worldjigsaw.chat;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ChatRequestMemoryProbe {
  public static void main(String[] args) throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
        socket.setSoTimeout(30000);
        var output = socket.getOutputStream();
        byte[] body =
            ("{\"schemaVersion\":1,\"messageId\":\"00000000-0000-4000-8000-000000000700\","
                    + "\"text\":\"hello\"}")
                .getBytes(StandardCharsets.UTF_8);
        int padding = 128 * 1024 * 1024;
        output.write(
            ("POST /api/v1/chat/messages HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "X-Local-Subject: 12345678-1234-1234-1234-123456789abc\r\nContent-Length: "
                    + (body.length + padding)
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) ' ');
        for (int sent = 0; sent < padding; sent += chunk.length) output.write(chunk);
        output.flush();
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String status = reader.readLine();
        if (status == null || !status.startsWith("HTTP/1.1 503 ")) {
          throw new AssertionError("Expected unavailable after parsing valid request: " + status);
        }
      }
    }
  }
}
