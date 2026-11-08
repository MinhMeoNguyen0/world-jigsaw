package com.worldjigsaw.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.Normalizer2;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Private local development relay. No message body is persisted or replayed. */
public final class ChatServer implements AutoCloseable {
  static {
    System.setProperty("sun.net.httpserver.drainAmount", "0");
  }

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final IdentityAdapter identityAdapter;
  private final ChatSettings settings;
  private final HttpServer server;
  private final Set<Subscriber> subscribers = new HashSet<>();
  private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor();

  private static final class Subscriber {
    final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(16);
    final Thread worker = Thread.currentThread();
    private boolean active = true;

    synchronized void terminate() {
      if (active) worker.interrupt();
    }

    synchronized void finish() {
      active = false;
    }
  }

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          32,
          32,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          new ThreadPoolExecutor.AbortPolicy());

  ChatServer(int port, IdentityAdapter identityAdapter, ChatSettings settings) throws IOException {
    this.identityAdapter = identityAdapter;
    this.settings = settings;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    expiry.shutdownNow();
    executor.shutdownNow();
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("CHAT_PORT", "8081"));
    ChatServer chat =
        new ChatServer(
            port,
            IdentityAdapter.fromEnvironment(System.getenv()),
            ChatSettings.fromEnvironment(System.getenv()));
    Runtime.getRuntime().addShutdownHook(new Thread(chat::close));
    chat.start();
    System.out.println("Local chat listening on 127.0.0.1:" + chat.port());
  }

  private void handle(HttpExchange exchange) throws IOException {
    // Early responses can leave the request body unread, so it cannot be reused.
    exchange.getResponseHeaders().set("Connection", "close");
    try {
      if (!localRequest(exchange)) {
        error(exchange, 403, "FORBIDDEN");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      if ((path.equals("/actuator/health")
                  || path.equals("/api/v1/chat/config")
                  || path.equals("/api/v1/chat/stream"))
              && !method.equals("GET")
          || path.equals("/api/v1/chat/messages") && !method.equals("POST")) {
        error(exchange, 405, "METHOD_NOT_ALLOWED");
        return;
      }
      if (path.equals("/actuator/health") && method.equals("GET")) {
        json(exchange, 200, Map.of("status", "UP"));
        return;
      }
      if (path.equals("/api/v1/chat/config") && method.equals("GET")) {
        json(
            exchange,
            200,
            Map.of(
                "schemaVersion",
                1,
                "maxGraphemes",
                settings.maxGraphemes(),
                "unicodeVersion",
                settings.unicodeVersion()));
        return;
      }
      if (!path.equals("/api/v1/chat/stream") && !path.equals("/api/v1/chat/messages")) {
        error(exchange, 404, "NOT_FOUND");
        return;
      }
      IdentityAdapter.Identity identity = identityAdapter.authenticate(exchange);
      if (identity == null) {
        error(exchange, 401, "UNAUTHENTICATED");
        return;
      }
      if (path.endsWith("/stream") && method.equals("GET")) {
        stream(exchange, identity);
        return;
      }
      if (path.endsWith("/messages") && method.equals("POST")) {
        send(exchange, identity);
        return;
      }
      error(exchange, 405, "METHOD_NOT_ALLOWED");
    } catch (RuntimeException e) {
      error(exchange, 500, "INTERNAL_ERROR");
    }
  }

  // Reject browser origins and Host values that are not explicit loopback, including DNS rebinding.
  private static boolean localRequest(HttpExchange e) {
    String host = e.getRequestHeaders().getFirst("Host");
    if (host == null || !host.matches("(?i)(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?"))
      return false;
    String origin = e.getRequestHeaders().getFirst("Origin");
    if (origin == null) return true;
    try {
      URI u = URI.create(origin);
      return "http".equals(u.getScheme())
          && ("localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost()))
          && u.getUserInfo() == null
          && (u.getPath() == null || u.getPath().isEmpty())
          && u.getQuery() == null
          && u.getFragment() == null;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private void stream(HttpExchange e, IdentityAdapter.Identity identity) throws IOException {
    Subscriber subscriber = new Subscriber();
    boolean accepted;
    synchronized (this) {
      accepted = subscribers.size() < 16;
      if (accepted) subscribers.add(subscriber);
    }
    if (!accepted) {
      error(e, 503, "CHAT_UNAVAILABLE");
      return;
    }
    ScheduledFuture<?> deadline =
        identity.expiresAt() == Long.MAX_VALUE
            ? null
            : expiry.schedule(
                subscriber::terminate,
                Math.max(0, identity.expiresAt() * 1000 - System.currentTimeMillis()),
                TimeUnit.MILLISECONDS);
    try {
      e.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
      e.getResponseHeaders().set("Cache-Control", "no-store");
      e.sendResponseHeaders(200, 0);
      var out = e.getResponseBody();
      out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
      while (!Thread.currentThread().isInterrupted()) {
        long secondsLeft = identity.expiresAt() - Instant.now().getEpochSecond();
        if (secondsLeft <= 0) break;
        String item = subscriber.queue.poll(Math.min(5, secondsLeft), TimeUnit.SECONDS);
        if (Instant.now().getEpochSecond() >= identity.expiresAt()) break;
        out.write(
            (item == null ? ": heartbeat\n\n" : "data: " + item + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
      }
    } catch (IOException ignored) {
      // Disconnected clients cannot receive messages.
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      subscriber.finish();
      if (deadline != null) deadline.cancel(false);
      synchronized (this) {
        subscribers.remove(subscriber);
      }
      e.close();
    }
  }

  private void send(HttpExchange e, IdentityAdapter.Identity identity) throws IOException {
    ChatRequest request;
    try {
      request = ChatRequest.read(e.getRequestBody(), settings.maxGraphemes());
    } catch (ChatRequest.TooLarge ex) {
      error(e, 413, "MESSAGE_TOO_LARGE");
      return;
    } catch (IOException ex) {
      error(e, 400, "INVALID_REQUEST");
      return;
    }
    String command = request.messageId();
    String text = request.text();
    String event =
        JSON.writeValueAsString(
            Map.of(
                "schemaVersion",
                1,
                "messageId",
                command,
                "displayName",
                identity.displayName(),
                "text",
                text,
                "sentAt",
                Instant.now().toString()));
    int status;
    synchronized (this) {
      if (System.currentTimeMillis() / 1000 >= identity.expiresAt()) {
        status = 401;
      } else if (subscribers.isEmpty()) {
        status = 503;
      } else {
        boolean offered = false;
        for (var iterator = subscribers.iterator(); iterator.hasNext(); ) {
          var subscriber = iterator.next();
          if (subscriber.queue.offer(event)) {
            offered = true;
          } else {
            iterator.remove();
            subscriber.terminate();
          }
        }
        status = offered ? 202 : 503;
      }
    }
    if (status == 202) {
      e.getResponseHeaders().remove("Connection");
      json(e, status, Map.of("schemaVersion", 1, "messageId", command));
    } else {
      error(e, status, status == 401 ? "UNAUTHENTICATED" : "CHAT_UNAVAILABLE");
    }
  }

  static String normalize(String input) {
    return normalize(input, 40);
  }

  static String normalize(String input, int maxGraphemes) {
    // Reject malformed UTF-16 and controls even when they occur at the edges.
    for (int i = 0; i < input.length(); ) {
      char c = input.charAt(i);
      if (Character.isSurrogate(c)
          && (!Character.isHighSurrogate(c)
              || i + 1 == input.length()
              || !Character.isLowSurrogate(input.charAt(i + 1)))) return null;
      int cp = input.codePointAt(i);
      if (UCharacter.getType(cp) == UCharacter.CONTROL) return null;
      i += Character.charCount(cp);
    }
    String value = Normalizer2.getNFCInstance().normalize(input);
    int start = 0, end = value.length();
    while (start < end
        && UCharacter.hasBinaryProperty(value.codePointAt(start), UProperty.WHITE_SPACE))
      start += Character.charCount(value.codePointAt(start));
    while (end > start
        && UCharacter.hasBinaryProperty(value.codePointBefore(end), UProperty.WHITE_SPACE))
      end -= Character.charCount(value.codePointBefore(end));
    value = value.substring(start, end);
    BreakIterator it = BreakIterator.getCharacterInstance(com.ibm.icu.util.ULocale.ROOT);
    it.setText(value);
    int count = 0;
    for (int pos = it.first(); (pos = it.next()) != BreakIterator.DONE; )
      if (++count > maxGraphemes) return null;
    return count == 0 ? null : value;
  }

  private static void error(HttpExchange e, int status, String code) throws IOException {
    json(
        e,
        status,
        Map.of(
            "schemaVersion",
            1,
            "error",
            Map.of(
                "code",
                code,
                "message",
                code,
                "requestId",
                UUID.randomUUID().toString(),
                "retryable",
                status == 503 || status == 500)));
  }

  private static void json(HttpExchange e, int status, Object value) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(value);
    e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    e.getResponseHeaders().set("Cache-Control", "no-store");
    e.sendResponseHeaders(status, bytes.length);
    try (var out = e.getResponseBody()) {
      out.write(bytes);
    }
  }
}
