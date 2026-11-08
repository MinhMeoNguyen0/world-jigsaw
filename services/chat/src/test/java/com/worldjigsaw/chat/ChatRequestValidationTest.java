package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChatRequestValidationTest {
  @Test
  void rejectsInvalidValuesBeforeReadingTheirUnboundedRemainder() throws Exception {
    try (var server = new ChatServer(0, IdentityAdapter.local(), new ChatSettings(40, "15.1"))) {
      server.start();
      for (String prefix :
          new String[] {
            "{\"messageId\":\"" + "a".repeat(40),
            "{\"unknown\": [",
            "{\"" + "z".repeat(20),
            "{\"text\":\"" + "x".repeat(1024),
            "{\"schemaVersion\":2"
          }) {
        try (var socket = new Socket()) {
          socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
          socket.setSoTimeout(3000);
          var out = socket.getOutputStream();
          out.write(
              ("POST /api/v1/chat/messages HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                      + "X-Local-Subject: 12345678-1234-1234-1234-123456789abc\r\n"
                      + "Content-Length: 134217728\r\n\r\n"
                      + prefix)
                  .getBytes(StandardCharsets.US_ASCII));
          out.flush();
          var reader =
              new BufferedReader(
                  new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
          String status = reader.readLine();
          assertNotNull(status, prefix);
          assertTrue(status.startsWith("HTTP/1.1 400 "), status);
        }
      }
    }
  }

  @Test
  void requestParserFollowsUnicodeFixtures() throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    try (var fixture = getClass().getResourceAsStream("/chat-wire-proposal.json")) {
      for (var vector : json.readTree(fixture).path("unicodeFixtures")) {
        byte[] body =
            json.writeValueAsBytes(
                java.util.Map.of(
                    "schemaVersion",
                    1,
                    "messageId",
                    "00000000-0000-4000-8000-000000000700",
                    "text",
                    vector.path("input").asText()));
        if (vector.path("valid").asBoolean()) {
          assertEquals(
              vector.path("normalized").asText(),
              ChatRequest.read(new java.io.ByteArrayInputStream(body), 40).text(),
              vector.path("name").asText());
        } else {
          assertThrows(
              java.io.IOException.class,
              () -> ChatRequest.read(new java.io.ByteArrayInputStream(body), 40),
              vector.path("name").asText());
        }
      }
    }
  }

  @Test
  void decodesEscapesAndPreservesLongClustersAtTheLimit() throws Exception {
    String id = "00000000-0000-4000-8000-000000000700";
    String longText = "x".repeat(39) + "a" + "\u0301".repeat(10000);
    String request =
        "{\"te\\u0078t\":\"" + longText + "\",\"schemaVersion\":1,\"messageId\":\"" + id + "\"}";
    var parsed =
        ChatRequest.read(
            new java.io.ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), 40);
    assertEquals(id, parsed.messageId());
    assertEquals(
        java.text.Normalizer.normalize(longText, java.text.Normalizer.Form.NFC), parsed.text());
    String tooLong = request.replace("x".repeat(39), "x".repeat(40));
    assertThrows(
        java.io.IOException.class,
        () ->
            ChatRequest.read(
                new java.io.ByteArrayInputStream(tooLong.getBytes(StandardCharsets.UTF_8)), 40));
    String trimmed =
        "{\"text\":\""
            + " ".repeat(100000)
            + "ok"
            + " ".repeat(100000)
            + "\",\"schemaVersion\":1,\"messageId\":\""
            + id
            + "\"}";
    assertEquals(
        "ok",
        ChatRequest.read(
                new java.io.ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)), 40)
            .text());
  }
}
