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
            "{\"text\":\"" + "a" + "\u0301".repeat(511) + "x".repeat(40),
            "{\"text\":\"" + "a" + "\\u0301".repeat(511) + "\\u0061".repeat(40),
            "{\"text\":\"" + "a" + "\u0301".repeat(511) + "🙂".repeat(40),
            "{\"text\":\"" + "a" + "\u0301".repeat(511) + " ".repeat(39) + "中",
            "{\"text\":\"" + "a" + "\u0301".repeat(511) + " ".repeat(40) + "\u0301",
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
                  .getBytes(StandardCharsets.UTF_8));
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
  void incrementalValidationMatchesUnicode151BoundaryFixtures() throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    try (var stream = getClass().getResourceAsStream("/GraphemeBreakTest-15.1.0.txt");
        var lines = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      int cases = 0;
      while ((line = lines.readLine()) != null) {
        String spec = line.split("#", 2)[0].trim();
        if (spec.isEmpty()) continue;
        var sample = new StringBuilder();
        for (String token : spec.split("\\s+")) {
          if (!token.equals("÷") && !token.equals("×"))
            sample.appendCodePoint(Integer.parseInt(token, 16));
        }
        if (sample
            .codePoints()
            .anyMatch(cp ->
                com.ibm.icu.lang.UCharacter.getType(cp) == com.ibm.icu.lang.UCharacter.CONTROL
                    || com.ibm.icu.lang.UCharacter.hasBinaryProperty(
                        cp, com.ibm.icu.lang.UProperty.WHITE_SPACE))) continue;
        String text = "a".repeat(39) + sample;
        var iterator =
            com.ibm.icu.text.BreakIterator.getCharacterInstance(com.ibm.icu.util.ULocale.ROOT);
        iterator.setText(text);
        int clusters = 0;
        for (int boundary = iterator.first();
            (boundary = iterator.next()) != com.ibm.icu.text.BreakIterator.DONE; ) clusters++;
        byte[] body =
            json.writeValueAsBytes(
                java.util.Map.of(
                    "schemaVersion",
                    1,
                    "messageId",
                    "00000000-0000-4000-8000-000000000700",
                    "text",
                    text));
        if (clusters <= 40) {
          assertEquals(
              java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC),
              ChatRequest.read(new java.io.ByteArrayInputStream(body), 40).text(),
              spec);
        } else {
          assertThrows(
              java.io.IOException.class,
              () -> ChatRequest.read(new java.io.ByteArrayInputStream(body), 40),
              spec);
        }
        cases++;
      }
      assertTrue(cases > 500);
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
