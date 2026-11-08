package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatServerTest {
  private final ObjectMapper json = new ObjectMapper();
  private ChatServer server;
  private HttpClient client;
  private String base;
  private final String subject = "12345678-1234-1234-1234-123456789abc";

  @BeforeEach
  void start() throws Exception {
    server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"));
    server.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    base = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3));
  }

  private HttpResponse<String> send(String id, String text) throws Exception {
    String body =
        json.writeValueAsString(
            java.util.Map.of("schemaVersion", 1, "messageId", id, "text", text));
    return client.send(
        request("/api/v1/chat/messages")
            .header("X-Local-Subject", subject)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private BufferedReader subscribe(String identity) throws Exception {
    HttpResponse<InputStream> response =
        client.send(
            request("/api/v1/chat/stream").header("X-Local-Subject", identity).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    assertEquals(": connected", reader.readLine());
    assertEquals("", reader.readLine());
    return reader;
  }

  private JsonNode event(BufferedReader r) throws Exception {
    String line = r.readLine();
    assertTrue(line.startsWith("data: "), line);
    assertEquals("", r.readLine());
    return json.readTree(line.substring(6));
  }

  @Test
  void liveFanoutRepeatedIdsAndNoReplay() throws Exception {
    try (BufferedReader a = subscribe(subject);
        BufferedReader b = subscribe("87654321-1234-1234-1234-123456789abc")) {
      String id = UUID.randomUUID().toString();
      HttpResponse<String> response = send(id, "  e\u0301 👩‍👩‍👧‍👦  ");
      assertEquals(202, response.statusCode());
      JsonNode first = event(a), second = event(b);
      assertEquals("é 👩‍👩‍👧‍👦", first.path("text").asText());
      assertEquals("Player 12345678", first.path("displayName").asText());
      assertFalse(first.has("type"));
      assertEquals(id, first.path("messageId").asText());
      assertEquals(first, second);
      assertEquals(
          first.path("messageId").asText(),
          json.readTree(response.body()).path("messageId").asText());
      assertEquals(202, send(id, "  e\u0301 👩‍👩‍👧‍👦  ").statusCode());
      assertEquals(first.path("text"), event(a).path("text"));
      assertEquals(first.path("text"), event(b).path("text"));
      assertEquals(202, send(id, "different").statusCode());
      assertEquals("different", event(a).path("text").asText());
      assertEquals("different", event(b).path("text").asText());
      b.close();
      try (BufferedReader c = subscribe("87654321-1234-1234-1234-123456789abc")) {
        assertEquals(202, send(UUID.randomUUID().toString(), "new").statusCode());
        assertEquals("new", event(c).path("text").asText());
        assertEquals("new", event(a).path("text").asText());
      }
    }
  }

  @Test
  void healthUnavailableValidationAndRepeatedSends() throws Exception {
    assertEquals(
        200,
        client
            .send(request("/actuator/health").GET().build(), HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(503, send(UUID.randomUUID().toString(), "offline").statusCode());
    assertEquals(
        401,
        client
            .send(
                request("/api/v1/chat/stream").GET().build(), HttpResponse.BodyHandlers.ofString())
            .statusCode());
    try (BufferedReader ignored = subscribe(subject)) {
      assertEquals(400, send(UUID.randomUUID().toString(), "x".repeat(41)).statusCode());
      assertEquals(400, send(UUID.randomUUID().toString(), "\tbad").statusCode());
      assertEquals(400, send(UUID.randomUUID().toString(), "\u2003").statusCode());
      assertEquals(400, send(UUID.randomUUID().toString(), "x".repeat(5000)).statusCode());
      for (int i = 0; i < 7; i++)
        assertEquals(
            202, send(UUID.randomUUID().toString(), "👨‍👩‍👧‍👦".repeat(40)).statusCode());
    }
  }

  @Test
  void largeValidMessagesAndErrors() throws Exception {
    try (BufferedReader live = subscribe(subject)) {
      String longCluster = "a" + "\u0301".repeat(3000);
      assertEquals(202, send(UUID.randomUUID().toString(), longCluster).statusCode());
      assertEquals(
          java.text.Normalizer.normalize(longCluster, java.text.Normalizer.Form.NFC),
          event(live).path("text").asText());
      String body =
          json.writeValueAsString(
                  java.util.Map.of(
                      "schemaVersion",
                      1,
                      "messageId",
                      UUID.randomUUID().toString(),
                      "text",
                      "hello"))
              + " ".repeat(5000);
      var largeBody =
          client.send(
              request("/api/v1/chat/messages")
                  .header("X-Local-Subject", subject)
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(202, largeBody.statusCode());
      assertEquals("hello", event(live).path("text").asText());
      var missing =
          client.send(
              request("/no-such-route").GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(404, missing.statusCode());
      assertEquals("NOT_FOUND", json.readTree(missing.body()).path("error").path("code").asText());
      var wrongMethod =
          client.send(
              request("/api/v1/chat/messages").GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(405, wrongMethod.statusCode());
      var error = json.readTree(wrongMethod.body());
      assertEquals(1, error.path("schemaVersion").asInt());
      assertEquals("METHOD_NOT_ALLOWED", error.path("error").path("code").asText());
      assertFalse(error.path("error").path("retryable").asBoolean());
      assertNotNull(UUID.fromString(error.path("error").path("requestId").asText()));
    }
  }

  @Test
  void paddingLargerThanHeapIsParsedIncrementally() throws Exception {
    String classpath =
        java.util.stream.Stream.of(
                ChatRequestMemoryProbe.class,
                ChatServer.class,
                com.ibm.icu.text.BreakIterator.class,
                com.fasterxml.jackson.databind.ObjectMapper.class,
                com.fasterxml.jackson.core.JsonParser.class,
                com.fasterxml.jackson.annotation.JsonInclude.class)
            .map(type -> {
              try {
                return java.nio.file.Path.of(
                        type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
              } catch (java.net.URISyntaxException ex) {
                throw new IllegalStateException(ex);
              }
            })
            .distinct()
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    var process =
        new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-cp",
                classpath,
                ChatRequestMemoryProbe.class.getName())
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(
          process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS), "memory probe timed out");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void proposedWireMessageAndMalformedJson() throws Exception {
    try (var fixtureStream = getClass().getResourceAsStream("/chat-wire-proposal.json");
        BufferedReader live = subscribe(subject)) {
      var fixtures = json.readTree(fixtureStream).path("wireFixtures");
      var post = fixtures.path("post");
      var response =
          client.send(
              request("/api/v1/chat/messages")
                  .header("X-Local-Subject", subject)
                  .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(post)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(202, response.statusCode());
      assertEquals(fixtures.path("accepted"), json.readTree(response.body()));
      var message = event(live);
      assertEquals(5, message.size());
      assertEquals(fixtures.path("sse").path("schemaVersion"), message.path("schemaVersion"));
      assertEquals(fixtures.path("sse").path("messageId"), message.path("messageId"));
      assertEquals(fixtures.path("sse").path("text"), message.path("text"));
      assertEquals("Player 12345678", message.path("displayName").asText());
      assertNotNull(java.time.Instant.parse(message.path("sentAt").asText()));
      var duplicateKeys =
          "{\"schemaVersion\":1,\"messageId\":\"00000000-0000-4000-8000-000000000700\",\"text\":\"a\",\"text\":\"b\"}";
      var invalid =
          client.send(
              request("/api/v1/chat/messages")
                  .header("X-Local-Subject", subject)
                  .POST(HttpRequest.BodyPublishers.ofString(duplicateKeys))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, invalid.statusCode());
      assertEquals(
          "INVALID_REQUEST", json.readTree(invalid.body()).path("error").path("code").asText());
      invalid =
          client.send(
              request("/api/v1/chat/messages")
                  .header("X-Local-Subject", subject)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xc3, 0x28}))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, invalid.statusCode());
      String validBody = json.writeValueAsString(post);
      for (byte[] suffix :
          new byte[][] {" {}".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xc3, 0x28}}) {
        byte[] prefix = validBody.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, bytes, prefix.length, suffix.length);
        invalid =
            client.send(
                request("/api/v1/chat/messages")
                    .header("X-Local-Subject", subject)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalid.statusCode());
      }
    }
  }

  @Test
  void stalledStreamsAreEvictedAndReleaseWorkers() throws Exception {
    java.util.List<java.net.Socket> stalled = new java.util.ArrayList<>();
    java.util.List<BufferedReader> replacements = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < 16; i++) {
        var socket = new java.net.Socket();
        socket.setReceiveBufferSize(1024);
        socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.port()));
        socket.setSoTimeout(3000);
        socket
            .getOutputStream()
            .write(
                ("GET /api/v1/chat/stream HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\nX-Local-Subject: "
                        + subject
                        + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        var in =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        assertTrue(in.readLine().contains("200"));
        while (!in.readLine().isEmpty()) {}
        stalled.add(socket);
      }
      String large = "a" + "\u0301".repeat(100000);
      int status = 202;
      for (int i = 0; i < 80 && status == 202; i++) {
        status = send(UUID.randomUUID().toString(), large).statusCode();
      }
      assertEquals(503, status);
      for (int i = 0; i < 16; i++) replacements.add(subscribe(subject));
      assertEquals(202, send(UUID.randomUUID().toString(), "available").statusCode());
      assertEquals("available", event(replacements.get(0)).path("text").asText());
    } finally {
      for (var socket : stalled) socket.close();
      for (var reader : replacements) reader.close();
    }
  }

  @Test
  void streamClosesWhenIdentityExpires() throws Exception {
    long expires = java.time.Instant.now().getEpochSecond() + 2;
    try (ChatServer shortLived =
        new ChatServer(
            0,
            exchange -> new IdentityAdapter.Identity(subject, "Player", expires),
            new ChatSettings(40, "15.1"))) {
      shortLived.start();
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + shortLived.port() + "/api/v1/chat/stream"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        assertEquals(200, response.statusCode());
        assertEquals(": connected", reader.readLine());
        assertEquals("", reader.readLine());
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> {
              try {
                assertNull(reader.readLine());
              } catch (java.io.IOException closedConnection) {
              }
            });
      }
    }
  }

  @Test
  void pinnedUnicodeExamples() {
    assertEquals("é", ChatServer.normalize("\u2003e\u0301\u2003"));
    assertEquals("🇺🇸".repeat(40), ChatServer.normalize("🇺🇸".repeat(40)));
    assertNull(ChatServer.normalize("🇺🇸".repeat(41)));
    assertEquals("क्ष".repeat(40), ChatServer.normalize("क्ष".repeat(40)));
    assertNull(ChatServer.normalize("क्ष".repeat(41)));
    assertNull(ChatServer.normalize("hello\n"));
    assertNull(ChatServer.normalize("\ud800"));
  }
}
