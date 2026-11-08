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
    String body = json.writeValueAsString(java.util.Map.of("commandId", id, "text", text));
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
      assertEquals(first, second);
      assertEquals(
          first.path("messageId").asText(),
          json.readTree(response.body()).path("messageId").asText());
      assertEquals(response.body(), send(id, "é 👩‍👩‍👧‍👦").body());
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
