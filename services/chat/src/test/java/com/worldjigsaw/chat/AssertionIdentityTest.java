package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssertionIdentityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SUB = "12345678-1234-1234-1234-123456789abc";
  private static final String JTI = "87654321-1234-1234-1234-123456789abc";
  private static final String ISSUER = "local-game";
  private final KeyPair key;

  AssertionIdentityTest() throws Exception {
    key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private Map<String, Object> claims() {
    long now = Instant.now().getEpochSecond();
    return new HashMap<>(
        Map.of(
            "schemaVersion",
            1,
            "iss",
            ISSUER,
            "aud",
            "world-jigsaw-chat",
            "sub",
            SUB,
            "jti",
            JTI,
            "iat",
            now,
            "exp",
            now + 120,
            "displayName",
            "Mira"));
  }

  private String token(Map<String, Object> claims, KeyPair signer, String keyId) throws Exception {
    var b64 = Base64.getUrlEncoder().withoutPadding();
    String header =
        b64.encodeToString(
            JSON.writeValueAsBytes(Map.of("alg", "EdDSA", "typ", "JWT", "kid", keyId)));
    String payload = b64.encodeToString(JSON.writeValueAsBytes(claims));
    String input = header + "." + payload;
    var signature = Signature.getInstance("Ed25519");
    signature.initSign(signer.getPrivate());
    signature.update(input.getBytes(StandardCharsets.US_ASCII));
    return input + "." + b64.encodeToString(signature.sign());
  }

  private HttpResponse<String> send(HttpClient client, int port, String token) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/messages"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"schemaVersion\":1,\"messageId\":\"00000000-0000-4000-8000-000000000700\",\"text\":\"hello\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void verifiesAssertionsLocallyAndRejectsBadClaimsAndSignatures() throws Exception {
    try (ChatServer server =
        new ChatServer(
            0, new AssertionIdentity(key.getPublic(), "key-1", ISSUER, Clock.systemUTC()))) {
      server.start();
      HttpClient client = HttpClient.newHttpClient();
      assertEquals(
          503,
          send(client, server.port(), token(claims(), key, "key-1"))
              .statusCode()); // authentic, no subscribers
      var bad = claims();
      bad.put("aud", "game");
      assertEquals(401, send(client, server.port(), token(bad, key, "key-1")).statusCode());
      bad = claims();
      bad.put("iss", "wrong");
      assertEquals(401, send(client, server.port(), token(bad, key, "key-1")).statusCode());
      bad = claims();
      bad.put("exp", Instant.now().getEpochSecond() - 1);
      assertEquals(401, send(client, server.port(), token(bad, key, "key-1")).statusCode());
      bad = claims();
      bad.put("iat", Instant.now().getEpochSecond() + 20);
      assertEquals(401, send(client, server.port(), token(bad, key, "key-1")).statusCode());
      bad = claims();
      bad.put("exp", Instant.now().getEpochSecond() + 301);
      assertEquals(401, send(client, server.port(), token(bad, key, "key-1")).statusCode());
      bad = claims();
      bad.put("displayName", "evil\nname");
      assertEquals(401, send(client, server.port(), token(bad, key, "key-1")).statusCode());
      bad = claims();
      bad.remove("schemaVersion");
      assertEquals(401, send(client, server.port(), token(bad, key, "key-1")).statusCode());
      assertEquals(401, send(client, server.port(), token(claims(), key, "wrong")).statusCode());
      assertEquals(
          401,
          send(
                  client,
                  server.port(),
                  token(
                      claims(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), "key-1"))
              .statusCode());
    }
  }

  @Test
  void authenticatedClientsReceiveSignedDisplayNameWithoutCredentialsOrHistory() throws Exception {
    try (ChatServer server =
        new ChatServer(
            0, new AssertionIdentity(key.getPublic(), "key-1", ISSUER, Clock.systemUTC()))) {
      server.start();
      HttpClient client = HttpClient.newHttpClient();
      String bearer = token(claims(), key, "key-1");
      var streamRequest =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.port() + "/api/v1/chat/stream"))
              .header("Authorization", "Bearer " + bearer)
              .GET()
              .build();
      var one = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
      var two = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
      try (var a =
              new java.io.BufferedReader(
                  new java.io.InputStreamReader(one.body(), StandardCharsets.UTF_8));
          var b =
              new java.io.BufferedReader(
                  new java.io.InputStreamReader(two.body(), StandardCharsets.UTF_8))) {
        assertEquals(200, one.statusCode());
        assertEquals(200, two.statusCode());
        assertEquals(": connected", a.readLine());
        assertEquals("", a.readLine());
        assertEquals(": connected", b.readLine());
        assertEquals("", b.readLine());
        assertEquals(202, send(client, server.port(), bearer).statusCode());
        String aEvent = a.readLine(), bEvent = b.readLine();
        assertEquals(aEvent, bEvent);
        var message = JSON.readTree(aEvent.substring(6));
        assertEquals("Mira", message.path("displayName").asText());
        assertEquals("hello", message.path("text").asText());
        assertEquals(5, message.size());
        assertFalse(aEvent.contains(bearer));
        assertFalse(aEvent.contains(SUB));
      }
    }
  }
}
