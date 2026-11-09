package com.worldjigsaw.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Local adapter or offline verification of a game-issued, short-lived chat assertion. */
interface IdentityAdapter {
  record Identity(String subject, String displayName, long expiresAt) {}

  Identity authenticate(HttpExchange exchange);

  static boolean canonicalUuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  static IdentityAdapter local() {
    return exchange -> {
      String subject = exchange.getRequestHeaders().getFirst("X-Local-Subject");
      return canonicalUuid(subject)
          ? new Identity(subject, "Player " + subject.substring(0, 8), Long.MAX_VALUE)
          : null;
    };
  }

  static IdentityAdapter fromEnvironment(Map<String, String> env) throws Exception {
    String mode = env.getOrDefault("CHAT_IDENTITY_MODE", "session");
    if (mode.equals("session")) {
      String port = env.getOrDefault("GAME_PORT", "8080");
      if (!port.matches("[0-9]{1,5}")
          || Integer.parseInt(port) < 1
          || Integer.parseInt(port) > 65535)
        throw new IllegalArgumentException("Invalid GAME_PORT");
      return session(URI.create("http://127.0.0.1:" + port + "/api/v1/session/identity"));
    }
    if (mode.equals("local")) return local();
    if (!mode.equals("assertion")) throw new IllegalArgumentException("Invalid CHAT_IDENTITY_MODE");
    String encoded = required(env, "CHAT_ASSERTION_PUBLIC_KEY");
    PublicKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    return new AssertionIdentity(
        key,
        required(env, "CHAT_ASSERTION_KEY_ID"),
        required(env, "CHAT_ASSERTION_ISSUER"),
        Clock.systemUTC());
  }

  static IdentityAdapter session(URI endpoint) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    ObjectMapper json = new ObjectMapper();
    return exchange -> {
      String token = null;
      var headers = exchange.getRequestHeaders().get("Cookie");
      if (headers != null)
        for (String header : headers)
          for (String part : header.split(";")) {
            String value = part.trim();
            if (value.startsWith("wj_session=")) {
              if (token != null) return null;
              token = value.substring("wj_session=".length());
            }
          }
      if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
      try {
        HttpRequest request =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Cookie", "wj_session=" + token)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonNode value = json.readTree(response.body());
        String subject = value.path("subject").asText();
        String name = value.path("displayName").asText();
        long expires = value.path("expiresAt").asLong();
        if (!canonicalUuid(subject)
            || name.length() > 128
            || !name.equals(ChatServer.normalize(name))
            || expires <= Instant.now().getEpochSecond()) return null;
        return new Identity(subject, name, expires);
      } catch (Exception ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        return null;
      }
    };
  }

  private static String required(Map<String, String> env, String key) {
    String value = env.get(key);
    if (value == null || value.isBlank() || value.length() > 256)
      throw new IllegalArgumentException("Missing or invalid " + key);
    return value;
  }
}

final class AssertionIdentity implements IdentityAdapter {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final String AUDIENCE = "world-jigsaw-chat";
  private final PublicKey key;
  private final String keyId;
  private final String issuer;
  private final Clock clock;

  AssertionIdentity(PublicKey key, String keyId, String issuer, Clock clock) {
    this.key = key;
    this.keyId = keyId;
    this.issuer = issuer;
    this.clock = clock;
  }

  private static String decodeUtf8(byte[] value) throws java.nio.charset.CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString();
  }

  @Override
  public Identity authenticate(HttpExchange exchange) {
    var values = exchange.getRequestHeaders().get("Authorization");
    if (values == null || values.size() != 1 || !values.getFirst().startsWith("Bearer "))
      return null;
    String token = values.getFirst().substring(7);
    if (token.length() > 2048) return null;
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3) return null;
    try {
      var decoder = Base64.getUrlDecoder();
      JsonNode header = JSON.readTree(decodeUtf8(decoder.decode(parts[0])));
      JsonNode claims = JSON.readTree(decodeUtf8(decoder.decode(parts[1])));
      if (header == null
          || !header.isObject()
          || header.size() != 3
          || claims == null
          || !claims.isObject()
          || claims.size() != 8
          || !claims.path("schemaVersion").isInt()
          || claims.path("schemaVersion").intValue() != 1
          || !"EdDSA".equals(header.path("alg").asText())
          || !"JWT".equals(header.path("typ").asText())
          || !keyId.equals(header.path("kid").asText())
          || header.has("crit")) return null;
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(decoder.decode(parts[2]))) return null;
      if (!claims.path("iss").isTextual()
          || !issuer.equals(claims.path("iss").textValue())
          || !claims.path("aud").isTextual()
          || !AUDIENCE.equals(claims.path("aud").textValue())
          || !claims.path("sub").isTextual()
          || !IdentityAdapter.canonicalUuid(claims.path("sub").textValue())
          || !claims.path("jti").isTextual()
          || !IdentityAdapter.canonicalUuid(claims.path("jti").textValue())
          || !claims.path("iat").isIntegralNumber()
          || !claims.path("iat").canConvertToLong()
          || !claims.path("exp").isIntegralNumber()
          || !claims.path("exp").canConvertToLong()) return null;
      long issued = claims.path("iat").longValue(), expires = claims.path("exp").longValue();
      long now = clock.instant().getEpochSecond();
      if (issued > now
          || issued < now - 300
          || expires <= now
          || expires <= issued
          || expires - issued > 300
          || claims.has("nbf")
          || !claims.path("displayName").isTextual()) return null;
      String displayName = claims.path("displayName").textValue();
      if (displayName.length() > 128 || !displayName.equals(ChatServer.normalize(displayName)))
        return null;
      return new Identity(claims.path("sub").textValue(), displayName, expires);
    } catch (Exception ex) {
      return null;
    }
  }
}
