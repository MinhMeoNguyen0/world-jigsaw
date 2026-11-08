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
    server = new ChatServer(0);
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
  void liveFanoutRetryConflictAndNoReplay() throws Exception {
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
      assertEquals(response.body(), send(id, "  e\u0301 👩‍👩‍👧‍👦  ").body());
      assertEquals(409, send(id, "é 👩‍👩‍👧‍👦").statusCode());
      assertEquals(409, send(id, "different").statusCode());
      b.close();
      try (BufferedReader c = subscribe("87654321-1234-1234-1234-123456789abc")) {
        assertEquals(202, send(UUID.randomUUID().toString(), "new").statusCode());
        assertEquals("new", event(c).path("text").asText());
        assertEquals("new", event(a).path("text").asText()); // retry did not fan out again
      }
    }
  }

  @Test
  void healthUnavailableValidationAndRateLimit() throws Exception {
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
      assertEquals(413, send(UUID.randomUUID().toString(), "x".repeat(5000)).statusCode());
      for (int i = 0; i < 5; i++)
        assertEquals(
            202, send(UUID.randomUUID().toString(), "👨‍👩‍👧‍👦".repeat(40)).statusCode());
      assertEquals(429, send(UUID.randomUUID().toString(), "six").statusCode());
    }
  }

  @Test
  void s3WireByteAndErrorFixtures() throws Exception {
    try (var fixtureStream = getClass().getResourceAsStream("/chat-wire-proposal.json");
        BufferedReader live = subscribe(subject)) {
      JsonNode fixtures = json.readTree(fixtureStream).path("wireFixtures");
      for (JsonNode vector : fixtures.path("textByteCases")) {
        String input = vector.path("input").asText();
        assertEquals(
            vector.path("utf8Bytes").asInt(), input.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(
            vector.path("withinByteLimit").asBoolean() ? 202 : 413,
            send(UUID.randomUUID().toString(), input).statusCode(),
            vector.path("name").asText());
      }
      for (JsonNode vector : fixtures.path("bodyByteCases")) {
        String body = vector.path("body").asText();
        assertEquals(
            vector.path("utf8Bytes").asInt(), body.getBytes(StandardCharsets.UTF_8).length);
        var response =
            client.send(
                request("/api/v1/chat/messages")
                    .header("X-Local-Subject", subject)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(
            vector.path("withinByteLimit").asBoolean() ? 202 : 413,
            response.statusCode(),
            vector.path("name").asText());
        if (response.statusCode() == 413)
          assertEquals(
              "MESSAGE_TOO_LARGE",
              json.readTree(response.body()).path("error").path("code").asText());
      }
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
