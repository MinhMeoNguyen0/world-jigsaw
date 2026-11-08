package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChatResponseLockTest {
  private static final String SUBJECT = "12345678-1234-1234-1234-123456789abc";
  private static final String BODY =
      "{\"schemaVersion\":1,\"messageId\":\"00000000-0000-4000-8000-000000000700\",\"text\":\"hello\"}";

  @Test
  void unavailableSendResponseDoesNotBlockSubscriptions() throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      var exchange = new BlockingExchange("POST", "/api/v1/chat/messages");
      withBlockedResponse(
          server,
          exchange,
          503,
          () -> {
            try (var stream = subscribe(server)) {
              assertEquals(202, send(server));
            }
          });
    }
  }

  @Test
  void acceptedSendResponseDoesNotBlockOtherSends() throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      try (var stream = subscribe(server)) {
        var exchange = new BlockingExchange("POST", "/api/v1/chat/messages");
        withBlockedResponse(server, exchange, 202, () -> assertEquals(202, send(server)));
      }
    }
  }

  @Test
  void expiredSendResponseDoesNotBlockOtherSends() throws Exception {
    IdentityAdapter identity =
        exchange ->
            new IdentityAdapter.Identity(
                SUBJECT,
                "Player",
                exchange.getRequestHeaders().containsKey("X-Test-Expired") ? 0 : Long.MAX_VALUE);
    try (var server = new ChatServer(0, identity, new ChatSettings(40, "15.1"))) {
      server.start();
      try (var stream = subscribe(server)) {
        var exchange = new BlockingExchange("POST", "/api/v1/chat/messages");
        exchange.request.set("X-Test-Expired", "yes");
        withBlockedResponse(server, exchange, 401, () -> assertEquals(202, send(server)));
      }
    }
  }

  @Test
  void fullStreamResponseDoesNotBlockSends() throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      List<InputStream> streams = new ArrayList<>();
      try {
        for (int i = 0; i < 16; i++) streams.add(subscribe(server));
        var exchange = new BlockingExchange("GET", "/api/v1/chat/stream");
        withBlockedResponse(server, exchange, 503, () -> assertEquals(202, send(server)));
      } finally {
        for (var stream : streams) stream.close();
      }
    }
  }

  private static InputStream subscribe(ChatServer server) throws Exception {
    var request =
        HttpRequest.newBuilder(uri(server, "/api/v1/chat/stream"))
            .header("X-Local-Subject", SUBJECT)
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
    var response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    return response.body();
  }

  private static int send(ChatServer server) throws Exception {
    var request =
        HttpRequest.newBuilder(uri(server, "/api/v1/chat/messages"))
            .header("X-Local-Subject", SUBJECT)
            .timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofString(BODY))
            .build();
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  private static URI uri(ChatServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private interface CheckedAction {
    void run() throws Exception;
  }

  private static void withBlockedResponse(
      ChatServer server, BlockingExchange exchange, int expectedStatus, CheckedAction action)
      throws Exception {
    var method = ChatServer.class.getDeclaredMethod("handle", HttpExchange.class);
    method.setAccessible(true);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var blocked =
          pool.submit(() -> {
            method.invoke(server, exchange);
            return null;
          });
      try {
        assertTrue(exchange.started.await(3, TimeUnit.SECONDS));
        assertEquals(expectedStatus, exchange.status);
        action.run();
      } finally {
        exchange.release.countDown();
      }
      blocked.get(3, TimeUnit.SECONDS);
    }
  }

  private static final class BlockingExchange extends HttpExchange {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final Headers request = new Headers();
    final Headers response = new Headers();
    final URI uri;
    final String method;
    volatile int status;

    BlockingExchange(String method, String path) {
      this.method = method;
      this.uri = URI.create(path);
      request.set("Host", "127.0.0.1");
      request.set("X-Local-Subject", SUBJECT);
    }

    @Override
    public Headers getRequestHeaders() {
      return request;
    }

    @Override
    public Headers getResponseHeaders() {
      return response;
    }

    @Override
    public URI getRequestURI() {
      return uri;
    }

    @Override
    public String getRequestMethod() {
      return method;
    }

    @Override
    public HttpContext getHttpContext() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public InputStream getRequestBody() {
      return new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputStream getResponseBody() {
      return new ByteArrayOutputStream();
    }

    @Override
    public void sendResponseHeaders(int status, long length) throws IOException {
      this.status = status;
      started.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS))
          throw new IOException("response remained blocked");
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException(ex);
      }
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public int getResponseCode() {
      return status;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public String getProtocol() {
      return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public void setStreams(InputStream in, OutputStream out) {}

    @Override
    public HttpPrincipal getPrincipal() {
      return null;
    }
  }
}
