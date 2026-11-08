package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(GameRolloverIntegrationTest.ClockConfig.class)
class GameRolloverIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  static final AtomicReference<Instant> NOW =
      new AtomicReference<>(Instant.parse("2026-11-07T23:59:20Z"));
  @Autowired GameBoards boards;
  @Autowired GameCoordinator game;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @LocalServerPort int port;
  private final ObjectMapper mapper = new ObjectMapper();

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    Clock clock() {
      return new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
          return NOW.get();
        }
      };
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  void admittedConfirmCompletesAfterCloseAndUnadmittedPreviewRollsOver() throws Exception {
    JsonNode today = fixture("sample-80.json"), tomorrow = fixture("review-60.json");
    boards.importBoard(
        LocalDate.parse("2026-11-07"), today.path("definition"), today.path("snapshot"));
    boards.importBoard(
        LocalDate.parse("2026-11-08"), tomorrow.path("definition"), tomorrow.path("snapshot"));
    String puzzle = today.path("snapshot").path("puzzleId").textValue(),
        nextPuzzle = tomorrow.path("snapshot").path("puzzleId").textValue();
    String origin = "http://127.0.0.1:" + port;
    HttpClient a = HttpClient.newHttpClient(), b = HttpClient.newHttpClient();
    String cookieA = session(a, origin), cookieB = session(b, origin);
    Listener la = new Listener(), lb = new Listener();
    WebSocket sa = socket(a, origin, cookieA, la), sb = socket(b, origin, cookieB, lb);
    send(sa, subscribe(puzzle));
    send(sb, subscribe(puzzle));
    assertThat(la.next().path("type").asText()).isEqualTo("subscribed");
    assertThat(lb.next().path("type").asText()).isEqualTo("subscribed");
    String turnA =
        post(a, origin, cookieA, "/api/v1/game/turns", begin(puzzle)).path("turnId").textValue();
    String turnB =
        post(b, origin, cookieB, "/api/v1/game/turns", begin(puzzle)).path("turnId").textValue();
    send(sa, preview(puzzle, turnA, today.path("snapshot").path("groups").get(0)));
    JsonNode actionA = la.next().path("action");
    lb.next();
    send(sb, preview(puzzle, turnB, today.path("snapshot").path("groups").get(1)));
    la.next();
    JsonNode actionB = lb.next().path("action");
    ObjectNode confirmA = confirm(puzzle, turnA, actionA),
        confirmB = confirm(puzzle, turnB, actionB);
    jdbc.execute(
        "CREATE FUNCTION block_test_board_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(88442211); RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER block_test_board_update BEFORE UPDATE OF board_version ON game_boards FOR EACH ROW EXECUTE FUNCTION block_test_board_update()");
    try (Connection lock = dataSource.getConnection();
        var statement = lock.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(88442211)");
      NOW.set(Instant.parse("2026-11-07T23:59:45Z"));
      var pending =
          a.sendAsync(
              postRequest(origin, cookieA, "/api/v1/game/turns/" + turnA + "/confirm", confirmA),
              HttpResponse.BodyHandlers.ofString());
      JsonNode pinnedA = la.next(), pinnedB = lb.next();
      assertThat(pinnedA).isEqualTo(pinnedB);
      assertThat(pinnedA.path("type").asText()).isEqualTo("preview");
      assertThat(pinnedA.path("turnId").asText()).isEqualTo(turnA);
      assertThat(Instant.parse(pinnedA.path("expiresAt").asText()))
          .isAfter(Instant.parse("2026-11-08T00:00:00Z"));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int waiters = 0;
        while (waiters == 0 && System.nanoTime() < deadline) {
          waiters =
              jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE game_boards SET board_version%'",
                  Integer.class);
          if (waiters == 0) Thread.sleep(20);
        }
        assertThat(waiters).isPositive();
        NOW.set(Instant.parse("2026-11-08T00:00:00Z"));
      } finally {
        statement.execute("SELECT pg_advisory_unlock(88442211)");
      }
      var response = pending.get(30, TimeUnit.SECONDS);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      JsonNode result = mapper.readTree(response.body());
      assertThat(result.path("outcome").asText()).isEqualTo("confirmed");
    }
    assertThat(la.next().path("type").asText()).isEqualTo("boardChanged");
    assertThat(lb.next().path("type").asText()).isEqualTo("boardChanged");
    game.tick();
    JsonNode clearA1 = la.next(), clearA2 = la.next(), clearB1 = lb.next(), clearB2 = lb.next();
    assertThat(clearA1).isEqualTo(clearB1);
    assertThat(clearA2).isEqualTo(clearB2);
    assertThat(
            java.util.Map.of(
                clearA1.path("turnId").asText(),
                clearA1.path("reason").asText(),
                clearA2.path("turnId").asText(),
                clearA2.path("reason").asText()))
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of(turnA, "confirmed", turnB, "rollover"));
    assertThat(clearA1.path("type").asText()).isEqualTo("previewCleared");
    assertThat(clearA2.path("type").asText()).isEqualTo("previewCleared");
    JsonNode changedA = la.next(), changedB = lb.next();
    assertThat(changedA.path("type").asText()).isEqualTo("puzzleChanged");
    assertThat(changedA).isEqualTo(changedB);
    assertThat(changedA.path("available").booleanValue()).isTrue();
    assertThat(changedA.path("puzzleId").asText()).isEqualTo(nextPuzzle);
    JsonNode late = post(b, origin, cookieB, "/api/v1/game/turns/" + turnB + "/confirm", confirmB);
    assertThat(late.path("outcome").asText()).isEqualTo("rejected");
    assertThat(late.path("error").path("code").asText()).isEqualTo("PUZZLE_CLOSED");
    assertThat(
            get(
                b,
                origin + "/api/v1/game/commands/" + confirmB.path("commandId").textValue(),
                cookieB))
        .isEqualTo(late);
    JsonNode newGame = get(b, origin + "/api/v1/game/current", cookieB);
    assertThat(newGame.path("snapshot").path("puzzleId").asText()).isEqualTo(nextPuzzle);
    assertThat(newGame.path("allowance").path("used").intValue()).isZero();
    assertThat(
            get(
                    a,
                    origin + "/api/v1/game/commands/" + confirmA.path("commandId").textValue(),
                    cookieA)
                .path("outcome")
                .asText())
        .isEqualTo("confirmed");
    send(sa, subscribe(nextPuzzle));
    send(sb, subscribe(nextPuzzle));
    assertThat(la.next().path("previews")).isEmpty();
    assertThat(lb.next().path("previews")).isEmpty();
    NOW.set(Instant.parse("2026-11-09T00:00:00Z"));
    game.tick();
    JsonNode missingA = la.next(), missingB = lb.next();
    assertThat(missingA).isEqualTo(missingB);
    assertThat(missingA.path("type").asText()).isEqualTo("puzzleChanged");
    assertThat(missingA.path("available").booleanValue()).isFalse();
    assertThat(missingA.path("puzzleId").isNull()).isTrue();
    var unavailable =
        b.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/game/current"))
                .header("Cookie", cookieB)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(mapper.readTree(unavailable.body()).path("error").path("code").asText())
        .isEqualTo("PUZZLE_UNAVAILABLE");
    sa.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
    sb.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
  }

  private JsonNode fixture(String name) throws Exception {
    return mapper.readTree(Path.of("../../packages/generator/fixtures/" + name).toFile());
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

  private WebSocket socket(HttpClient client, String origin, String cookie, Listener listener)
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

  private ObjectNode subscribe(String puzzle) {
    return mapper
        .createObjectNode()
        .put("schemaVersion", 1)
        .put("type", "subscribe")
        .put("puzzleId", puzzle)
        .putNull("afterBoardVersion");
  }

  private ObjectNode preview(String puzzle, String turn, JsonNode group) {
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
            .put("actionId", UUID.randomUUID().toString())
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

  private ObjectNode confirm(String puzzle, String turn, JsonNode action) {
    ObjectNode c =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle)
            .put("turnId", turn)
            .put("commandId", UUID.randomUUID().toString())
            .put("turnRevision", 0)
            .put("generation", 0);
    c.putArray("actions").add(action);
    return c;
  }

  private void send(WebSocket socket, JsonNode value) throws Exception {
    socket.sendText(value.toString(), true).get(5, TimeUnit.SECONDS);
  }

  private HttpRequest postRequest(String origin, String cookie, String path, JsonNode body) {
    return HttpRequest.newBuilder(URI.create(origin + path))
        .header("Origin", origin)
        .header("Cookie", cookie)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();
  }

  private JsonNode post(HttpClient client, String origin, String cookie, String path, JsonNode body)
      throws Exception {
    var response =
        client.send(postRequest(origin, cookie, path, body), HttpResponse.BodyHandlers.ofString());
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
      String text = messages.poll(20, TimeUnit.SECONDS);
      if (text == null) throw new AssertionError("WebSocket reply timed out");
      return mapper.readTree(text);
    }
  }
}
