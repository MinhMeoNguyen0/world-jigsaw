package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.worldjigsaw.WorldJigsawApplication;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Restarts the application context and HTTP server while retaining only the PostgreSQL database.
 */
@Testcontainers
class GameRestartIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void lostHttpReplyAndApplicationRestartRecoverCommitAndDiscardDraft() throws Exception {
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode snapshot = board.path("snapshot"),
        g0 = snapshot.path("groups").get(0),
        g1 = snapshot.path("groups").get(1);
    String puzzle = snapshot.path("puzzleId").textValue();
    ConfigurableApplicationContext first = start();
    JsonNode confirm;
    JsonNode saved;
    String cookie;
    try {
      assertThatThrownBy(this::start).hasRootCauseMessage("Another game writer owns this database");
      first
          .getBean(GameBoards.class)
          .importBoard(LocalDate.now(ZoneOffset.UTC), board.path("definition"), snapshot);
      int port = ((WebServerApplicationContext) first).getWebServer().getPort();
      String origin = "http://127.0.0.1:" + port;
      HttpClient client = HttpClient.newHttpClient();
      cookie = session(client, origin);
      Listener listener = new Listener();
      WebSocket socket = connect(client, origin, cookie, listener);
      socket.sendText(subscribe(puzzle, null).toString(), true).get();
      assertThat(listener.next().path("type").asText()).isEqualTo("subscribed");
      JsonNode turn = post(client, origin + "/api/v1/game/turns", origin, cookie, begin(puzzle));
      String turnId = turn.path("turnId").textValue(), actionId = UUID.randomUUID().toString();
      socket.sendText(preview(puzzle, turnId, actionId, g0).toString(), true).get();
      JsonNode frame = listener.next();
      assertThat(frame.path("type").asText()).isEqualTo("preview");
      String command = UUID.randomUUID().toString();
      ObjectNode payload =
          mapper
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("puzzleId", puzzle)
              .put("turnId", turnId)
              .put("commandId", command)
              .put("turnRevision", 0)
              .put("generation", 0);
      payload.putArray("actions").add(frame.path("action"));
      confirm = payload;
      // Deliver the complete request and close the HTTP socket without reading its reply.
      sendAndDrop(
          port, origin, cookie, "/api/v1/game/turns/" + turnId + "/confirm", confirm.toString());
      assertThat(listener.next().path("type").asText()).isEqualTo("preview");
      assertThat(listener.next().path("type").asText()).isEqualTo("boardChanged");
      assertThat(listener.next().path("type").asText()).isEqualTo("previewCleared");
      saved = get(client, origin + "/api/v1/game/commands/" + command, cookie);
      assertThat(saved.path("outcome").asText()).isEqualTo("confirmed");
      assertThat(saved.path("allowance").path("used").intValue()).isEqualTo(1);
      JsonNode unconfirmed =
          post(client, origin + "/api/v1/game/turns", origin, cookie, begin(puzzle));
      socket
          .sendText(
              preview(
                      puzzle,
                      unconfirmed.path("turnId").textValue(),
                      UUID.randomUUID().toString(),
                      g1)
                  .toString(),
              true)
          .get();
      assertThat(listener.next().path("type").asText()).isEqualTo("preview");
    } finally {
      first.close();
    }

    try (ConfigurableApplicationContext second = start()) {
      int port = ((WebServerApplicationContext) second).getWebServer().getPort();
      String origin = "http://127.0.0.1:" + port;
      HttpClient client = HttpClient.newHttpClient();
      assertThat(
              get(
                  client,
                  origin + "/api/v1/game/commands/" + confirm.path("commandId").textValue(),
                  cookie))
          .isEqualTo(saved);
      assertThat(
              post(
                  client,
                  origin + "/api/v1/game/turns/" + confirm.path("turnId").textValue() + "/confirm",
                  origin,
                  cookie,
                  confirm))
          .isEqualTo(saved);
      Listener listener = new Listener();
      WebSocket socket = connect(client, origin, cookie, listener);
      socket
          .sendText(subscribe(puzzle, saved.path("boardVersion").longValue()).toString(), true)
          .get();
      JsonNode resync = listener.next();
      assertThat(resync.path("type").asText()).isEqualTo("resyncRequired");
      assertThat(resync.path("reason").asText()).isEqualTo("restart");
      socket.sendText(subscribe(puzzle, null).toString(), true).get();
      JsonNode fresh = listener.next();
      assertThat(fresh.path("type").asText()).isEqualTo("subscribed");
      assertThat(fresh.path("snapshot").path("boardVersion").longValue())
          .isEqualTo(saved.path("boardVersion").longValue());
      assertThat(fresh.path("previews")).isEmpty();
      JsonNode restored = null;
      for (JsonNode group : fresh.path("snapshot").path("groups"))
        if (group.path("groupId").equals(g1.path("groupId"))) restored = group;
      assertThat(restored).isNotNull();
      assertThat(restored.path("transform")).isEqualTo(g1.path("transform"));
      JsonNode current = get(client, origin + "/api/v1/game/current", cookie);
      assertThat(current.path("allowance").path("used").intValue()).isEqualTo(1);
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
    }
  }

  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(WorldJigsawApplication.class)
        .run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.main.banner-mode=off",
            "--logging.level.root=WARN");
  }

  private String session(HttpClient client, String origin) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/session"))
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
  }

  private WebSocket connect(HttpClient client, String origin, String cookie, Listener listener)
      throws Exception {
    return client
        .newWebSocketBuilder()
        .header("Origin", origin)
        .header("Cookie", cookie)
        .buildAsync(
            URI.create(origin.replace("http://", "ws://") + "/api/v1/game/stream"), listener)
        .get();
  }

  private ObjectNode begin(String puzzle) {
    return mapper
        .createObjectNode()
        .put("schemaVersion", 1)
        .put("puzzleId", puzzle)
        .put("requestId", UUID.randomUUID().toString());
  }

  private ObjectNode subscribe(String puzzle, Long cursor) {
    ObjectNode o =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "subscribe")
            .put("puzzleId", puzzle);
    if (cursor == null) o.putNull("afterBoardVersion");
    else o.put("afterBoardVersion", cursor);
    return o;
  }

  private ObjectNode preview(String puzzle, String turn, String actionId, JsonNode group) {
    ObjectNode p =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "preview.submit")
            .put("puzzleId", puzzle)
            .put("turnId", turn)
            .put("generation", 0)
            .put("sequence", 1);
    ObjectNode a =
        p.putObject("action")
            .put("actionId", actionId)
            .put("kind", "piece")
            .put("targetId", group.path("pieceIds").get(0).textValue());
    a.putArray("affectedPieceIds").add(group.path("pieceIds").get(0).textValue());
    a.putArray("baseRevisions")
        .addObject()
        .put("groupId", group.path("groupId").textValue())
        .put("revision", group.path("revision").longValue());
    ObjectNode t = a.putObject("intendedTransform");
    t.putObject("translation").put("x", 0).put("y", 0);
    t.put("rotation", 0);
    return p;
  }

  private JsonNode post(HttpClient client, String url, String origin, String cookie, JsonNode body)
      throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Origin", origin)
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private JsonNode get(HttpClient client, String url, String cookie) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private void sendAndDrop(int port, String origin, String cookie, String path, String body)
      throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    try (Socket socket = new Socket("127.0.0.1", port)) {
      String headers =
          "POST "
              + path
              + " HTTP/1.1\r\nHost: 127.0.0.1:"
              + port
              + "\r\nOrigin: "
              + origin
              + "\r\nCookie: "
              + cookie
              + "\r\nContent-Type: application/json\r\nContent-Length: "
              + bytes.length
              + "\r\nConnection: close\r\n\r\n";
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().write(bytes);
      socket.getOutputStream().flush();
    }
  }

  private final class Listener implements WebSocket.Listener {
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      socket.request(1);
      return null;
    }

    JsonNode next() throws Exception {
      String text = messages.poll(25, TimeUnit.SECONDS);
      if (text == null) throw new AssertionError("WebSocket reply timed out");
      return mapper.readTree(text);
    }
  }
}
