package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChatOverloadTest {
  @Test
  void excessConnectionsAreRejectedAndDispatcherRecovers() throws Exception {
    CountDownLatch entered = new CountDownLatch(32);
    CountDownLatch release = new CountDownLatch(1);
    IdentityAdapter stalled =
        exchange -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new IdentityAdapter.Identity(
              "12345678-1234-1234-1234-123456789abc", "Player", Long.MAX_VALUE);
        };
    var sockets = new ArrayList<Socket>();
    try (var server = new ChatServer(0, stalled, new ChatSettings(40, "15.1"))) {
      server.start();
      try {
        for (int i = 0; i < 32; i++) sockets.add(request(server, "/api/v1/chat/messages"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 64; i++) sockets.add(request(server, "/api/v1/chat/messages"));
        var field = ChatServer.class.getDeclaredField("executor");
        field.setAccessible(true);
        var executor = (ThreadPoolExecutor) field.get(server);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.getQueue().size() < 64 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(64, executor.getQueue().size());
        try (var excess = request(server, "/api/v1/chat/stream")) {
          excess.setSoTimeout(3000);
          try {
            assertEquals(-1, excess.getInputStream().read());
          } catch (java.net.SocketException closed) {
            assertTrue(excess.isConnected());
          }
        }
        release.countDown();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!executor.getQueue().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(executor.getQueue().isEmpty());
        var client = java.net.http.HttpClient.newHttpClient();
        var health =
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + server.port() + "/actuator/health"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
        assertEquals(
            200,
            client.send(health, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
      } finally {
        release.countDown();
        for (var socket : sockets) socket.close();
      }
    }
  }

  private static Socket request(ChatServer server, String path) throws Exception {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
    String method = path.endsWith("stream") ? "GET" : "POST";
    socket
        .getOutputStream()
        .write(
            (method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + "Content-Length: 0\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
    return socket;
  }
}
