package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionIdentityTest {
  @Test
  void verifiedCookieControlsStreamAndSenderAcrossReconnects() throws Exception {
    String cookie = "wj_session=" + "a".repeat(43);
    String subject = "12345678-1234-1234-1234-123456789abc";
    HttpServer game = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    game.createContext(
        "/identity",
        exchange -> {
          boolean valid = cookie.equals(exchange.getRequestHeaders().getFirst("Cookie"));
          byte[] body =
              valid
                  ? new ObjectMapper()
                      .writeValueAsBytes(
                          Map.of(
                              "subject",
                              subject,
                              "displayName",
                              "Player12345678",
                              "expiresAt",
                              Instant.now().getEpochSecond() + 60))
                  : new byte[0];
          exchange.sendResponseHeaders(valid ? 200 : 401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    game.start();
    try (ChatServer chat =
        new ChatServer(
            0,
            IdentityAdapter.session(
                URI.create("http://127.0.0.1:" + game.getAddress().getPort() + "/identity")),
            new ChatSettings(40, "15.1"))) {
      chat.start();
      HttpClient client = HttpClient.newHttpClient();
      String base = "http://127.0.0.1:" + chat.port();
      assertEquals(
          401,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(base + "/api/v1/chat/stream"))
                      .header("X-Local-Subject", subject)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      for (int i = 0; i < 2; i++) {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/chat/stream"))
                    .header("Cookie", cookie)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          assertEquals(": connected", reader.readLine());
          assertEquals("", reader.readLine());
          String payload =
              "{\"schemaVersion\":1,\"messageId\":\"00000000-0000-4000-8000-000000000700\",\"text\":\"hello\"}";
          var sent =
              client.send(
                  HttpRequest.newBuilder(URI.create(base + "/api/v1/chat/messages"))
                      .header("Cookie", cookie)
                      .header("X-Local-Subject", "87654321-1234-1234-1234-123456789abc")
                      .POST(HttpRequest.BodyPublishers.ofString(payload))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(202, sent.statusCode());
          String line = reader.readLine();
          assertEquals(
              "Player12345678",
              new ObjectMapper().readTree(line.substring(6)).path("displayName").asText());
        }
      }
    } finally {
      game.stop(0);
    }
  }
}
