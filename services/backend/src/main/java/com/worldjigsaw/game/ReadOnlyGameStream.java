package com.worldjigsaw.game;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Snapshot-only stream; game mutations and previews remain unavailable. */
@Component
class ReadOnlyGameStream extends TextWebSocketHandler {
  private static final long MAX_SAFE = 9_007_199_254_740_991L;
  private final ObjectMapper mapper =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final GameBoards boards;
  private final Clock clock;
  private final Semaphore connections = new Semaphore(100);

  ReadOnlyGameStream(GameBoards boards, Clock clock) {
    this.boards = boards;
    this.clock = clock;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    if (!connections.tryAcquire()) {
      session.close(new CloseStatus(1013, "capacity"));
      return;
    }
    session.getAttributes().put("permit", true);
    session.setTextMessageSizeLimit(4096);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    if (session.getAttributes().remove("permit") != null) connections.release();
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    Instant expiry = (Instant) session.getAttributes().get("expiry");
    if (expiry == null || !clock.instant().isBefore(expiry)) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    JsonNode request;
    try {
      request = mapper.readTree(message.getPayload());
    } catch (Exception e) {
      error(session, "INVALID_REQUEST", "Invalid message", false);
      return;
    }
    if (request == null
        || !request.isObject()
        || request.size() != 4
        || !request.path("schemaVersion").isIntegralNumber()
        || !request.path("schemaVersion").canConvertToLong()
        || request.path("schemaVersion").longValue() != 1
        || !request.path("type").isTextual()
        || !"subscribe".equals(request.path("type").textValue())
        || !request.path("puzzleId").isTextual()
        || !request.has("afterBoardVersion")) {
      error(session, "INVALID_REQUEST", "Only snapshot subscriptions are available", false);
      return;
    }
    UUID puzzle;
    try {
      String input = request.path("puzzleId").textValue();
      if (!input.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        throw new IllegalArgumentException("invalid UUID");
      puzzle = UUID.fromString(input);
    } catch (IllegalArgumentException e) {
      error(session, "INVALID_REQUEST", "Invalid puzzle", false);
      return;
    }
    JsonNode cursor = request.path("afterBoardVersion");
    if (!cursor.isNull()
        && (!cursor.isIntegralNumber()
            || !cursor.canConvertToLong()
            || cursor.longValue() < 0
            || cursor.longValue() > MAX_SAFE)) {
      error(session, "INVALID_REQUEST", "Invalid cursor", false);
      return;
    }
    try {
      JsonNode snapshot = mapper.valueToTree(boards.current().get("snapshot"));
      if (!puzzle.equals(BoardValidator.id(snapshot, "puzzleId"))) {
        error(session, "PUZZLE_UNAVAILABLE", "Puzzle is unavailable", true);
        return;
      }
      if (!cursor.isNull() && cursor.longValue() != snapshot.path("boardVersion").longValue()) {
        ObjectNode resync = mapper.createObjectNode();
        resync
            .put("schemaVersion", 1)
            .put("type", "resyncRequired")
            .put("puzzleId", puzzle.toString())
            .put("reason", "history_expired");
        send(session, resync);
        return;
      }
      ObjectNode reply = mapper.createObjectNode();
      reply.put("schemaVersion", 1).put("type", "subscribed");
      reply.set("snapshot", snapshot);
      reply.put("watermark", snapshot.path("boardVersion").longValue());
      reply.putArray("previews");
      send(session, reply);
      // There is no event fanout yet. Do not leave a connection that appears live but can go stale.
      session.close(new CloseStatus(1013, "snapshot only"));
    } catch (PuzzleUnavailable ex) {
      error(session, "PUZZLE_UNAVAILABLE", "Puzzle is unavailable", true);
    }
  }

  private void error(WebSocketSession session, String code, String message, boolean retryable)
      throws IOException {
    ObjectNode reply = mapper.createObjectNode();
    reply.put("schemaVersion", 1).put("type", "error");
    reply
        .putObject("error")
        .put("code", code)
        .put("message", message)
        .put("requestId", UUID.randomUUID().toString())
        .put("retryable", retryable);
    send(session, reply);
  }

  private void send(WebSocketSession session, JsonNode message) throws IOException {
    session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
  }
}

@Component
class LocalStreamHandshake implements HandshakeInterceptor {
  private final LocalSessions sessions;

  LocalStreamHandshake(LocalSessions sessions) {
    this.sessions = sessions;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    if (!(request instanceof ServletServerHttpRequest servlet)
        || !LocalSessions.sameOrigin(servlet.getServletRequest())) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    LocalSessions.Session session =
        sessions.lookup(LocalSessions.cookie(servlet.getServletRequest()));
    if (session == null) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    attributes.put("subject", session.subject());
    attributes.put("expiry", session.expiresAt());
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {}
}

@Configuration
@EnableWebSocket
class StreamConfiguration implements WebSocketConfigurer {
  private final ReadOnlyGameStream stream;
  private final LocalStreamHandshake handshake;

  StreamConfiguration(ReadOnlyGameStream stream, LocalStreamHandshake handshake) {
    this.stream = stream;
    this.handshake = handshake;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(stream, "/api/v1/game/stream").addInterceptors(handshake);
  }
}
