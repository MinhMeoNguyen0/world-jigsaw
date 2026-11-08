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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(GameLegacyIntegrationTest.ClockConfig.class)
class GameLegacyIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  @Autowired GameBoards boards;
  @LocalServerPort int port;
  private final ObjectMapper mapper = new ObjectMapper();

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  void importedArbitraryAnglesRemainReadableAndInteractionReturnsExplicitUnsupported()
      throws Exception {
    JsonNode fixture =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    boards.importBoard(
        LocalDate.parse("2026-11-07"), fixture.path("definition"), fixture.path("snapshot"));
    String origin = "http://127.0.0.1:" + port,
        puzzle = fixture.path("snapshot").path("puzzleId").textValue();
    HttpClient client = HttpClient.newHttpClient();
    var session =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/session"))
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String cookie = session.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    JsonNode before = get(client, origin, cookie);
    assertThat(before.path("snapshot")).isEqualTo(fixture.path("snapshot"));
    Listener listener = new Listener();
    WebSocket socket =
        client
            .newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookie)
            .buildAsync(
                URI.create(origin.replace("http://", "ws://") + "/api/v1/game/stream"), listener)
            .get();
    ObjectNode subscribe =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "subscribe")
            .put("puzzleId", puzzle)
            .putNull("afterBoardVersion");
    socket.sendText(subscribe.toString(), true).get();
    assertThat(listener.next().path("snapshot")).isEqualTo(fixture.path("snapshot"));
    ObjectNode begin =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle)
            .put("requestId", UUID.randomUUID().toString());
    JsonNode turn = post(client, origin, cookie, "/api/v1/game/turns", begin, 200);
    String turnId = turn.path("turnId").textValue();
    JsonNode group = fixture.path("snapshot").path("groups").get(0);
    ObjectNode action =
        mapper
            .createObjectNode()
            .put("actionId", UUID.randomUUID().toString())
            .put("kind", "group")
            .put("targetId", group.path("groupId").textValue());
    action.set("affectedPieceIds", group.path("pieceIds").deepCopy());
    action
        .putArray("baseRevisions")
        .addObject()
        .put("groupId", group.path("groupId").textValue())
        .put("revision", group.path("revision").longValue());
    ObjectNode transform = action.putObject("intendedTransform");
    transform.putObject("translation").put("x", 0).put("y", 0);
    transform.put("rotation", 0);
    ObjectNode preview =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "preview.submit")
            .put("puzzleId", puzzle)
            .put("turnId", turnId)
            .put("generation", 0)
            .put("sequence", 1);
    preview.set("action", action);
    socket.sendText(preview.toString(), true).get();
    JsonNode rejected = listener.next();
    assertThat(rejected.path("type").asText()).isEqualTo("preview.rejected");
    assertThat(rejected.path("error").path("code").asText()).isEqualTo("INVALID_ACTION");
    assertThat(rejected.path("error").path("message").asText())
        .isEqualTo("UNSUPPORTED_LEGACY_ORIENTATION");
    ObjectNode confirm =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle)
            .put("turnId", turnId)
            .put("commandId", UUID.randomUUID().toString())
            .put("turnRevision", 0)
            .put("generation", 0);
    confirm.putArray("actions").add(action);
    JsonNode unsupported =
        post(client, origin, cookie, "/api/v1/game/turns/" + turnId + "/confirm", confirm, 400);
    assertThat(unsupported.path("error").path("message").asText())
        .isEqualTo("UNSUPPORTED_LEGACY_ORIENTATION");
    JsonNode after = get(client, origin, cookie);
    assertThat(after.path("snapshot")).isEqualTo(before.path("snapshot"));
    assertThat(after.path("allowance").path("used").intValue()).isZero();
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
  }

  private JsonNode post(
      HttpClient client, String origin, String cookie, String path, JsonNode body, int status)
      throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + path))
                .header("Origin", origin)
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return mapper.readTree(response.body());
  }

  private JsonNode get(HttpClient client, String origin, String cookie) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/game/current"))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
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
      String text = messages.poll(10, TimeUnit.SECONDS);
      if (text == null) throw new AssertionError("WebSocket reply timed out");
      return mapper.readTree(text);
    }
  }
}
