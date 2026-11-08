package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class GameWriterGuardIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  @Autowired GameBoards boards;
  @Autowired GameWriterGuard writer;
  @Autowired LocalSessions sessions;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @LocalServerPort int port;
  private final ObjectMapper mapper = new ObjectMapper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  void lossOfWriterConnectionClosesStreamsAndRejectsNewStateReads() throws Exception {
    JsonNode board =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    boards.importBoard(
        LocalDate.now(ZoneOffset.UTC), board.path("definition"), board.path("snapshot"));
    String origin = "http://127.0.0.1:" + port;
    HttpClient client = HttpClient.newHttpClient();
    var session =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/session"))
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String cookie = session.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    Listener listener = new Listener();
    WebSocket socket =
        client
            .newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookie)
            .buildAsync(
                URI.create(origin.replace("http://", "ws://") + "/api/v1/game/stream"), listener)
            .get();
    String subscribe =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "subscribe")
            .put("puzzleId", board.path("snapshot").path("puzzleId").textValue())
            .putNull("afterBoardVersion")
            .toString();
    socket.sendText(subscribe, true).get();
    assertThat(listener.subscribed.get(10, TimeUnit.SECONDS)).isTrue();
    long key = 0x574a494753415645L, high = key >>> 32, low = key & 0xffffffffL;
    Integer pid =
        jdbc.queryForObject(
            "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND classid::bigint = ? AND objid::bigint = ? AND granted",
            Integer.class,
            high,
            low);
    assertThat(pid).isNotNull();
    assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid)).isTrue();
    assertThat(listener.closed.get(10, TimeUnit.SECONDS)).isTrue();
    assertThat(writer.healthy()).isFalse();
    Integer sessionCount = jdbc.queryForObject("SELECT count(*) FROM game_sessions", Integer.class);
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> sessions.getOrCreate(null)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM game_sessions", Integer.class))
        .isEqualTo(sessionCount);
    var current =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/game/current"))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(current.statusCode()).isEqualTo(500);
    assertThat(mapper.readTree(current.body()).path("error").path("code").asText())
        .isEqualTo("INTERNAL_ERROR");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(() -> new GameWriterGuard(dataSource)))
        .isInstanceOf(IllegalStateException.class);
    writer.close();
    GameWriterGuard successor = new GameWriterGuard(dataSource);
    try {
      assertThat(successor.healthy()).isTrue();
      var oldWriterRead =
          client.send(
              HttpRequest.newBuilder(URI.create(origin + "/api/v1/game/current"))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(oldWriterRead.statusCode()).isEqualTo(500);
      assertThat(mapper.readTree(oldWriterRead.body()).path("error").path("code").asText())
          .isEqualTo("INTERNAL_ERROR");
    } finally {
      successor.close();
    }
  }

  private final class Listener implements WebSocket.Listener {
    final CompletableFuture<Boolean> subscribed = new CompletableFuture<>(),
        closed = new CompletableFuture<>();
    final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        try {
          if ("subscribed".equals(mapper.readTree(partial.toString()).path("type").asText()))
            subscribed.complete(true);
        } catch (Exception ex) {
          subscribed.completeExceptionally(ex);
        }
        partial.setLength(0);
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
      closed.complete(true);
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      closed.complete(true);
    }
  }
}
