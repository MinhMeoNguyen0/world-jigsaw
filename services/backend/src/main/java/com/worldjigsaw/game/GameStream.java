package com.worldjigsaw.game;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
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

/** Authenticated local WebSocket. Each peer has a bounded, isolated outgoing queue. */
@Component
class GameStream extends TextWebSocketHandler {
  private final ObjectMapper mapper =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final GameCoordinator game;
  private final GameWireValidator wire;
  private final Clock clock;
  private final GameWriterGuard writerGuard;
  private final Semaphore slots = new Semaphore(100);
  private final Map<String, Peer> peers = new ConcurrentHashMap<>();
  private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

  GameStream(
      GameCoordinator game, GameWireValidator wire, Clock clock, GameWriterGuard writerGuard) {
    this.game = game;
    this.wire = wire;
    this.clock = clock;
    this.writerGuard = writerGuard;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    if (!slots.tryAcquire()) {
      session.close(new CloseStatus(1013, "capacity"));
      return;
    }
    session.setTextMessageSizeLimit(65536);
    Peer peer =
        new Peer(
            session,
            (UUID) session.getAttributes().get("subject"),
            (String) session.getAttributes().get("displayName"),
            (Instant) session.getAttributes().get("expiry"));
    peers.put(session.getId(), peer);
    peer.writer = writers.submit(peer::writeLoop);
  }

  /** Snapshot only currently subscribed, unexpired connections; multiple tabs count once. */
  List<Map<String, String>> connectedPlayers(UUID puzzleId) {
    Map<UUID, String> names = new java.util.HashMap<>();
    Instant now = clock.instant();
    for (Peer peer : peers.values()) {
      if (puzzleId.equals(peer.puzzle)
          && !peer.stopped.get()
          && !peer.overloaded.get()
          && now.isBefore(peer.expiresAt)) names.put(peer.subject, peer.displayName);
    }
    return names.values().stream().sorted().map(name -> Map.of("displayName", name)).toList();
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    cleanup(session);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    cleanup(session);
    try {
      session.close(CloseStatus.SERVER_ERROR);
    } catch (IOException ignored) {
    }
  }

  private void cleanup(WebSocketSession session) {
    Peer peer = peers.remove(session.getId());
    if (peer == null || !peer.cleaned.compareAndSet(false, true)) return;
    clearCursor(peer);
    game.unsubscribe(peer.id);
    peer.stopped.set(true);
    if (peer.writer != null && Thread.currentThread() != peer.writerThread)
      peer.writer.cancel(true);
    slots.release();
    if (peers.values().stream().noneMatch(other -> other.subject.equals(peer.subject))) {
      try {
        game.disconnected(peer.subject);
      } catch (IllegalStateException ignored) {
        /* Writer has stopped. */
      }
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
  void expireSessions() {
    boolean writerHealthy = writerGuard.healthy();
    for (Peer peer : peers.values()) {
      if ((!writerHealthy || !clock.instant().isBefore(peer.expiresAt))
          && peer.expiring.compareAndSet(false, true)) {
        writers.submit(() -> {
          try {
            peer.session.close(CloseStatus.POLICY_VIOLATION);
          } catch (IOException ignored) {
            cleanup(peer.session);
          }
        });
      }
    }
  }

  @PreDestroy
  void shutdown() {
    for (Peer peer : peers.values()) {
      try {
        peer.session.close(CloseStatus.GOING_AWAY);
      } catch (IOException ignored) {
      }
    }
    writers.shutdownNow();
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    Peer peer = peers.get(session.getId());
    if (peer == null) return;
    if (!clock.instant().isBefore(peer.expiresAt) || !peer.allowFrame()) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    JsonNode envelope;
    try {
      envelope = mapper.readTree(message.getPayload());
    } catch (IOException ex) {
      peer.sendError("INVALID_REQUEST", "Invalid message", false);
      return;
    }
    String type = envelope == null ? "" : envelope.path("type").asText();
    String schema =
        switch (type) {
          case "subscribe" -> "Subscribe";
          case "subscribe.v2" -> "SubscribeV2";
          case "cursor.update" -> "CursorUpdate";
          case "preview.submit" -> "PreviewSubmit";
          case "heartbeat" -> "Heartbeat";
          default -> null;
        };
    if (schema == null) {
      peer.sendError("INVALID_REQUEST", "Unsupported message", false);
      return;
    }
    JsonNode payload;
    try {
      payload = wire.parse(schema, message.getPayload());
    } catch (IllegalArgumentException ex) {
      peer.sendError("INVALID_REQUEST", "Invalid message", false);
      return;
    }
    UUID puzzle = BoardValidator.id(payload, "puzzleId");
    if ("subscribe".equals(type) || "subscribe.v2".equals(type)) {
      try {
        Long cursor =
            payload.path("afterBoardVersion").isNull()
                ? null
                : payload.path("afterBoardVersion").longValue();
        game.subscribe(peer.id, peer.subject, puzzle, cursor, peer, "subscribe.v2".equals(type));
      } catch (RuntimeException ex) {
        peer.sendError(code(ex), message(ex), retryable(ex));
      }
      return;
    }
    if (!puzzle.equals(peer.puzzle)) {
      peer.sendError("INVALID_REQUEST", "Subscribe before sending game messages", false);
      return;
    }
    if ("cursor.update".equals(type)) {
      peer.cursorEnabled = true;
      broadcastCursor(peer, payload.path("position"));
      return;
    }
    if ("heartbeat".equals(type)) {
      try {
        TurnLeaseRegistry.Info info = game.heartbeat(peer.subject, payload);
        ObjectNode response =
            mapper
                .createObjectNode()
                .put("schemaVersion", 1)
                .put("type", "leaseRenewed")
                .put("puzzleId", info.puzzle().toString())
                .put("turnId", info.turn().toString())
                .put("generation", info.generation())
                .put("expiresAt", info.expiresAt().toString());
        peer.offerOrSlow(response);
      } catch (RuntimeException ex) {
        peer.sendError(code(ex), message(ex), retryable(ex));
      }
      return;
    }
    try {
      game.preview(peer.subject, payload, peer.id);
    } catch (RuntimeException ex) {
      ObjectNode rejected =
          mapper
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("type", "preview.rejected")
              .put("puzzleId", puzzle.toString())
              .put("turnId", payload.path("turnId").textValue())
              .put("generation", payload.path("generation").longValue())
              .put("sequence", payload.path("sequence").longValue())
              .put("actionId", payload.path("action").path("actionId").textValue());
      rejected.set("error", errorNode(code(ex), message(ex), retryable(ex)));
      peer.offerOrSlow(rejected);
    }
  }

  private String code(RuntimeException ex) {
    String code =
        ex instanceof DraftRules.RuleError e
            ? e.code
            : ex instanceof TurnLeaseRegistry.LeaseError e
                ? e.code
                : ex instanceof PuzzleUnavailable
                    ? "PUZZLE_UNAVAILABLE"
                    : ex instanceof IllegalArgumentException ? "INVALID_REQUEST" : "INTERNAL_ERROR";
    return "UNSUPPORTED_LEGACY_ORIENTATION".equals(code) ? "INVALID_ACTION" : code;
  }

  private String message(RuntimeException ex) {
    if (ex instanceof DraftRules.RuleError e && "UNSUPPORTED_LEGACY_ORIENTATION".equals(e.code))
      return "UNSUPPORTED_LEGACY_ORIENTATION";
    if (ex instanceof DraftRules.RuleError || ex instanceof TurnLeaseRegistry.LeaseError)
      return ex.getMessage();
    return "Unable to process request";
  }

  private boolean retryable(RuntimeException ex) {
    return "INTERNAL_ERROR".equals(code(ex)) || "PUZZLE_UNAVAILABLE".equals(code(ex));
  }

  private ObjectNode errorNode(String code, String message, boolean retryable) {
    return mapper
        .createObjectNode()
        .put("code", code)
        .put("message", message)
        .put("requestId", UUID.randomUUID().toString())
        .put("retryable", retryable);
  }

  private void clearCursor(Peer peer) {
    if (peer.cursorEnabled && peer.puzzle != null) broadcastCursor(peer, mapper.nullNode());
    peer.cursorEnabled = false;
  }

  private void broadcastCursor(Peer source, JsonNode position) {
    UUID puzzle = source.puzzle;
    if (puzzle == null) return;
    ObjectNode event =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "cursor.presence")
            .put("puzzleId", puzzle.toString())
            .put("cursorId", source.id.toString())
            .put("displayName", source.displayName);
    event.set("position", position);
    for (Peer target : peers.values()) {
      if (target != source
          && target.cursorEnabled
          && puzzle.equals(target.puzzle)
          && clock.instant().isBefore(target.expiresAt)
          && target.queue.size() < 32) target.offer(event);
    }
  }

  private final class Peer implements GameCoordinator.Sink {
    final UUID id = UUID.randomUUID(), subject;
    final String displayName;
    final WebSocketSession session;
    final Instant expiresAt;
    final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(128);
    final AtomicBoolean stopped = new AtomicBoolean(),
        overloaded = new AtomicBoolean(),
        cleaned = new AtomicBoolean(),
        expiring = new AtomicBoolean();
    volatile UUID puzzle;
    volatile boolean cursorEnabled;
    volatile Future<?> writer;
    volatile Thread writerThread;
    private long window = System.nanoTime();
    private int frames;

    Peer(WebSocketSession session, UUID subject, String displayName, Instant expiresAt) {
      this.session = session;
      this.subject = subject;
      this.displayName = displayName;
      this.expiresAt = expiresAt;
    }

    synchronized boolean allowFrame() {
      long now = System.nanoTime();
      if (now - window >= 1_000_000_000L) {
        window = now;
        frames = 0;
      }
      return ++frames <= 120;
    }

    @Override
    public boolean offer(JsonNode value) {
      if (stopped.get() || overloaded.get()) return false;
      String type = value.path("type").asText();
      if ("subscribed".equals(type) || "subscribed.v2".equals(type)) {
        UUID next = UUID.fromString(value.path("snapshot").path("puzzleId").textValue());
        if (puzzle != null && !puzzle.equals(next)) clearCursor(this);
        puzzle = next;
      }
      if ("resyncRequired".equals(type) || "puzzleChanged".equals(type)) {
        clearCursor(this);
        puzzle = null;
      }
      return queue.offer(value.toString());
    }

    void offerOrSlow(JsonNode value) {
      if (!offer(value)) slow();
    }

    @Override
    public void slow() {
      if (!overloaded.compareAndSet(false, true)) return;
      UUID current = puzzle;
      clearCursor(this);
      puzzle = null;
      queue.clear();
      if (current != null)
        queue.offer(
            mapper
                .createObjectNode()
                .put("schemaVersion", 1)
                .put("type", "resyncRequired")
                .put("puzzleId", current.toString())
                .put("reason", "slow_consumer")
                .toString());
      else queue.offer("");
    }

    void sendError(String code, String message, boolean retryable) {
      ObjectNode value = mapper.createObjectNode().put("schemaVersion", 1).put("type", "error");
      value.set("error", errorNode(code, message, retryable));
      offerOrSlow(value);
    }

    void writeLoop() {
      writerThread = Thread.currentThread();
      try {
        while (!stopped.get()) {
          String value = queue.take();
          if (!value.isEmpty()) session.sendMessage(new TextMessage(value));
          if (overloaded.get() && queue.isEmpty()) {
            session.close(new CloseStatus(1013, "slow consumer"));
            break;
          }
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      } catch (IOException | RuntimeException ex) {
        try {
          session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
        }
      } finally {
        cleanup(session);
      }
    }
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
    attributes.put("displayName", session.displayName());
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
@EnableScheduling
class StreamConfiguration implements WebSocketConfigurer {
  private final GameStream stream;
  private final LocalStreamHandshake handshake;

  StreamConfiguration(GameStream stream, LocalStreamHandshake handshake) {
    this.stream = stream;
    this.handshake = handshake;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(stream, "/api/v1/game/stream").addInterceptors(handshake);
  }
}
