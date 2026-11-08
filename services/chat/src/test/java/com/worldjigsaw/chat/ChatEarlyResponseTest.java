package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatEarlyResponseTest {
  private static final String SUBJECT = "12345678-1234-1234-1234-123456789abc";

  private record Request(
      String method, String path, String host, String subject, String body, int status) {}

  @Test
  void unfinishedBodiesDoNotHoldWorkersAfterResponses() throws Exception {
    var requests =
        List.of(
            new Request(
                "POST",
                "/api/v1/chat/messages",
                "127.0.0.1",
                SUBJECT,
                "{\"text\":\"" + "x".repeat(41),
                400),
            new Request("POST", "/api/v1/chat/messages", "127.0.0.1", null, "", 401),
            new Request("GET", "/api/v1/chat/messages", "127.0.0.1", SUBJECT, "", 405),
            new Request("GET", "/missing", "127.0.0.1", SUBJECT, "", 404),
            new Request("POST", "/api/v1/chat/messages", "example.com", SUBJECT, "", 403),
            new Request("GET", "/actuator/health", "127.0.0.1", null, "", 200),
            new Request("GET", "/api/v1/chat/config", "127.0.0.1", null, "", 200));
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      var held = new ArrayList<Socket>();
      try {
        for (int i = 0; i < 7; i++) {
          for (var request : requests) {
            Socket socket = open(server, request, i % 2 == 0);
            held.add(socket);
            assertStatus(socket, request.status());
          }
        }
        try (var health =
            open(
                server,
                new Request("GET", "/actuator/health", "127.0.0.1", null, "", 200),
                false)) {
          assertStatus(health, 200);
        }
        for (var socket : held) assertClosed(socket);
      } finally {
        for (var socket : held) socket.close();
      }
    }
  }

  @Test
  void fullStreamRejectionsDoNotHoldWorkers() throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      var held = new ArrayList<Socket>();
      try {
        var stream = new Request("GET", "/api/v1/chat/stream", "127.0.0.1", SUBJECT, "", 200);
        for (int i = 0; i < 16; i++) {
          Socket socket = openStream(server, stream);
          held.add(socket);
          assertStatus(socket, 200);
        }
        for (int i = 0; i < 24; i++) {
          Socket socket = open(server, stream, i % 2 == 0);
          held.add(socket);
          assertStatus(socket, 503);
        }
        try (var health =
            open(
                server,
                new Request("GET", "/actuator/health", "127.0.0.1", null, "", 200),
                false)) {
          assertStatus(health, 200);
        }
      } finally {
        for (var socket : held) socket.close();
      }
    }
  }

  @Test
  void slowValidUploadsRemainConnected() throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      try (var fixed = connect(server); var chunked = connect(server)) {
        String body =
            "{\"schemaVersion\":1,\"messageId\":\"00000000-0000-4000-8000-000000000700\",\"text\":\"hi\"}";
        var request = new Request("POST", "/api/v1/chat/messages", "127.0.0.1", SUBJECT, "", 0);
        write(fixed, request, "Content-Length: " + body.length() + "\r\n", "{");
        write(chunked, request, "Transfer-Encoding: chunked\r\n", "1\r\n{\r\n");
        Thread.sleep(7000);
        fixed.getOutputStream().write(body.substring(1).getBytes(StandardCharsets.UTF_8));
        fixed.getOutputStream().flush();
        String remainder = body.substring(1);
        chunked
            .getOutputStream()
            .write(
                (Integer.toHexString(remainder.length()) + "\r\n" + remainder + "\r\n0\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        chunked.getOutputStream().flush();
        assertStatus(fixed, 503);
        assertStatus(chunked, 503);
      }
    }
  }

  @Test
  void oversizedChunkedUploadIsRejectedWithoutWaitingForEnd() throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      try (var socket = connect(server)) {
        String body =
            "{\"schemaVersion\":1,\"messageId\":\"00000000-0000-4000-8000-000000000700\",\"text\":\"hi\"}";
        String payload = body + " ".repeat(65537 - body.length());
        write(
            socket,
            new Request("POST", "/api/v1/chat/messages", "127.0.0.1", SUBJECT, "", 0),
            "Transfer-Encoding: chunked\r\n",
            Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n");
        assertStatus(socket, 413);
      }
    }
  }

  private static Socket open(ChatServer server, Request request, boolean chunked) throws Exception {
    Socket socket = connect(server);
    String framing = chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 134217728\r\n";
    String prefix = chunked ? "10000\r\n" + request.body() : request.body();
    write(socket, request, framing, prefix);
    return socket;
  }

  private static Socket openStream(ChatServer server, Request request) throws Exception {
    Socket socket = connect(server);
    write(socket, request, "", "");
    return socket;
  }

  private static Socket connect(ChatServer server) throws Exception {
    var socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
    socket.setSoTimeout(3000);
    return socket;
  }

  private static void write(Socket socket, Request request, String framing, String prefix)
      throws Exception {
    String subject =
        request.subject() == null ? "" : "X-Local-Subject: " + request.subject() + "\r\n";
    socket
        .getOutputStream()
        .write(
            (request.method()
                    + " "
                    + request.path()
                    + " HTTP/1.1\r\n"
                    + "Host: "
                    + request.host()
                    + "\r\n"
                    + subject
                    + framing
                    + "\r\n"
                    + prefix)
                .getBytes(StandardCharsets.UTF_8));
    socket.getOutputStream().flush();
  }

  private static void assertClosed(Socket socket) throws Exception {
    try {
      while (socket.getInputStream().read() != -1) {}
    } catch (java.net.SocketException closed) {
      assertTrue(socket.isConnected());
    }
  }

  private static void assertStatus(Socket socket, int expected) throws Exception {
    var reader =
        new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
    String status = reader.readLine();
    assertNotNull(status);
    assertTrue(status.startsWith("HTTP/1.1 " + expected + " "), status);
  }
}
