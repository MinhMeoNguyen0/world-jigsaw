package com.worldjigsaw.game;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private loopback-only anonymous identity; it is not public authentication. */
@Service
class LocalSessions {
  static final String COOKIE = "wj_session";
  private static final Duration LIFETIME = Duration.ofDays(30);
  private final SecureRandom random = new SecureRandom();
  private final JdbcTemplate jdbc;
  private final Clock clock;

  record Session(UUID subject, String displayName, Instant expiresAt, String newToken) {}

  LocalSessions(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  Session lookup(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
    List<Session> sessions =
        jdbc.query(
            "SELECT subject_id, display_name, expires_at FROM game_sessions WHERE token_hash = ? AND expires_at > ?",
            (rs, row) ->
                new Session(
                    rs.getObject("subject_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getTimestamp("expires_at").toInstant(),
                    null),
            hash(token),
            java.sql.Timestamp.from(clock.instant()));
    return sessions.isEmpty() ? null : sessions.getFirst();
  }

  @Transactional
  public Session getOrCreate(String token) {
    Session existing = lookup(token);
    if (existing != null) return existing;
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    UUID subject = UUID.randomUUID();
    String display = "Player" + subject.toString().substring(0, 8);
    Instant expiry =
        clock.instant().plus(LIFETIME).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    jdbc.update("INSERT INTO game_subjects(subject_id) VALUES (?)", subject);
    jdbc.update(
        "INSERT INTO game_sessions(token_hash, subject_id, display_name, expires_at) VALUES (?, ?, ?, ?)",
        hash(secret),
        subject,
        display,
        java.sql.Timestamp.from(expiry));
    return new Session(subject, display, expiry, secret);
  }

  static String cookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    String token = null;
    if (cookies != null)
      for (Cookie c : cookies)
        if (COOKIE.equals(c.getName())) {
          if (token != null) return null;
          token = c.getValue();
        }
    return token;
  }

  /** Do not trust forwarded headers; same-origin and literal loopback hosts only. */
  static boolean sameOrigin(HttpServletRequest request) {
    String host = request.getServerName();
    if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) return false;
    String value = request.getHeader("Origin");
    if (value == null || value.length() > 512) return false;
    try {
      URI origin = URI.create(value);
      int port =
          origin.getPort() < 0 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
      return origin.getScheme().equals(request.getScheme())
          && origin.getHost() != null
          && origin.getHost().equalsIgnoreCase(host)
          && port == request.getServerPort()
          && origin.getRawUserInfo() == null
          && origin.getRawQuery() == null
          && origin.getRawFragment() == null
          && (origin.getRawPath() == null || origin.getRawPath().isEmpty());
    } catch (IllegalArgumentException | NullPointerException e) {
      return false;
    }
  }

  private static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}

@RestController
class LocalSessionController {
  private final LocalSessions sessions;

  LocalSessionController(LocalSessions sessions) {
    this.sessions = sessions;
  }

  @PostMapping("/api/v1/session")
  ResponseEntity<?> session(HttpServletRequest request) {
    if (!LocalSessions.sameOrigin(request))
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(
              Map.of(
                  "schemaVersion",
                  1,
                  "error",
                  Map.of(
                      "code",
                      "FORBIDDEN",
                      "message",
                      "Origin is not allowed",
                      "requestId",
                      UUID.randomUUID().toString(),
                      "retryable",
                      false)));
    LocalSessions.Session session = sessions.getOrCreate(LocalSessions.cookie(request));
    var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (session.newToken() != null) {
      response.header(
          HttpHeaders.SET_COOKIE,
          ResponseCookie.from(LocalSessions.COOKIE, session.newToken())
              .httpOnly(true)
              .secure(request.isSecure())
              .sameSite("Strict")
              .path("/")
              .maxAge(Duration.ofDays(30))
              .build()
              .toString());
    }
    return response.body(
        Map.of(
            "schemaVersion",
            1,
            "displayName",
            session.displayName(),
            "expiresAt",
            session.expiresAt().toString()));
  }
}
