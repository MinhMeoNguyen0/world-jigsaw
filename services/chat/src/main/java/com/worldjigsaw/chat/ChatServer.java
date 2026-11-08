package com.worldjigsaw.chat;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Private local development relay. No message body is persisted or replayed. */
public final class ChatServer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_BODY = 4096;
  private final HttpServer server;
  private final Set<ArrayBlockingQueue<String>> subscribers = new HashSet<>();
  private final Map<String, ArrayDeque<Long>> rates = new HashMap<>();
  private final Map<String, Receipt> receipts = new HashMap<>();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          32,
          32,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          new ThreadPoolExecutor.CallerRunsPolicy());

  private record Receipt(byte[] digest, long expires, String id) {}

  public ChatServer(int port) throws IOException {
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
    executor.shutdownNow();
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("CHAT_PORT", "8081"));
    ChatServer chat = new ChatServer(port);
    Runtime.getRuntime().addShutdownHook(new Thread(chat::close));
    chat.start();
    System.out.println("Local chat listening on 127.0.0.1:" + chat.port());
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      if (!localRequest(exchange)) {
        error(exchange, 403, "FORBIDDEN");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      if (path.equals("/actuator/health") && method.equals("GET")) {
        json(exchange, 200, Map.of("status", "UP"));
        return;
      }
      if (path.equals("/api/v1/chat/config") && method.equals("GET")) {
        json(
            exchange,
            200,
            Map.of("schemaVersion", 1, "maxGraphemes", 40, "unicodeVersion", "15.1"));
        return;
      }
      if (!path.equals("/api/v1/chat/stream") && !path.equals("/api/v1/chat/messages")) {
        error(exchange, 404, "NOT_FOUND");
        return;
      }
      String subject = exchange.getRequestHeaders().getFirst("X-Local-Subject");
      try {
        if (subject == null || !UUID.fromString(subject).toString().equals(subject))
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        error(exchange, 401, "LOCAL_IDENTITY_REQUIRED");
        return;
      }
      if (path.endsWith("/stream") && method.equals("GET")) {
        stream(exchange);
        return;
      }
      if (path.endsWith("/messages") && method.equals("POST")) {
        send(exchange, subject);
        return;
      }
      error(exchange, 405, "METHOD_NOT_ALLOWED");
    } catch (RuntimeException e) {
      exchange.close();
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

  private void stream(HttpExchange e) throws IOException {
    ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(16);
    synchronized (this) {
      if (subscribers.size() >= 16) {
        error(e, 503, "UNAVAILABLE");
        return;
      }
      subscribers.add(queue);
    }
    try {
      e.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
      e.getResponseHeaders().set("Cache-Control", "no-store");
      e.sendResponseHeaders(200, 0);
      var out = e.getResponseBody();
      out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
      while (!Thread.currentThread().isInterrupted()) {
        String item = queue.poll(5, TimeUnit.SECONDS);
        if ("".equals(item)) break;
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
      synchronized (this) {
        subscribers.remove(queue);
      }
      e.close();
    }
  }

  private void send(HttpExchange e, String subject) throws IOException {
    byte[] body = e.getRequestBody().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) {
      error(e, 413, "PAYLOAD_TOO_LARGE");
      return;
    }
    JsonNode input;
    try {
      input = JSON.readTree(body);
    } catch (IOException ex) {
      error(e, 400, "INVALID_INPUT");
      return;
    }
    if (input == null
        || !input.isObject()
        || input.size() != 2
        || !input.path("commandId").isTextual()
        || !input.path("text").isTextual()) {
      error(e, 400, "INVALID_INPUT");
      return;
    }
    String command = input.path("commandId").textValue();
    try {
      if (!UUID.fromString(command).toString().equals(command))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) {
      error(e, 400, "INVALID_INPUT");
      return;
    }
    String text = normalize(input.path("text").textValue());
    if (text == null) {
      error(e, 400, "INVALID_TEXT");
      return;
    }
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
    synchronized (this) {
      long now = System.currentTimeMillis();
      receipts.values().removeIf(r -> r.expires() <= now);
      rates
          .values()
          .forEach(q -> {
            while (!q.isEmpty() && q.peekFirst() <= now - 10000) q.removeFirst();
          });
      rates.values().removeIf(ArrayDeque::isEmpty);
      String key = subject + ":" + command;
      Receipt previous = receipts.get(key);
      if (previous != null) {
        if (!Arrays.equals(previous.digest(), digest)) {
          error(e, 409, "COMMAND_CONFLICT");
          return;
        }
        json(e, 202, Map.of("schemaVersion", 1, "messageId", previous.id()));
        return;
      }
      if (subscribers.isEmpty()
          || receipts.size() >= 4096
          || (rates.size() >= 1024 && !rates.containsKey(subject))) {
        error(e, 503, "UNAVAILABLE");
        return;
      }
      ArrayDeque<Long> rate = rates.computeIfAbsent(subject, unused -> new ArrayDeque<>());
      if (rate.size() >= 5) {
        error(e, 429, "RATE_LIMITED");
        return;
      }
      String id = UUID.randomUUID().toString();
      String event =
          JSON.writeValueAsString(
              Map.of(
                  "schemaVersion",
                  1,
                  "type",
                  "message",
                  "messageId",
                  id,
                  "alias",
                  "Player " + subject.substring(0, 8),
                  "text",
                  text,
                  "sentAt",
                  Instant.now().toString()));
      boolean offered = false;
      for (var iterator = subscribers.iterator(); iterator.hasNext(); ) {
        var subscriber = iterator.next();
        if (subscriber.offer(event)) {
          offered = true;
        } else {
          iterator.remove();
          subscriber.clear();
          subscriber.offer(""); // terminate a slow stream rather than retain a backlog
        }
      }
      if (!offered) {
        error(e, 503, "UNAVAILABLE");
        return;
      }
      rate.addLast(now);
      receipts.put(key, new Receipt(digest, now + 60000, id));
      json(e, 202, Map.of("schemaVersion", 1, "messageId", id));
    }
  }

  static String normalize(String input) {
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
      if (++count > 40) return null;
    return count == 0 ? null : value;
  }

  private static void error(HttpExchange e, int status, String code) throws IOException {
    json(
        e,
        status,
        Map.of(
            "code",
            code,
            "message",
            code,
            "requestId",
            UUID.randomUUID().toString(),
            "retryable",
            status == 429 || status == 503));
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
