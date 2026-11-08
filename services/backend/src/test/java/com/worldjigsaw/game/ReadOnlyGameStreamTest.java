package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
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
@Import(ReadOnlyGameStreamTest.ClockConfig.class)
class ReadOnlyGameStreamTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  @LocalServerPort int port;
  @Autowired GameBoards boards;
  private final ObjectMapper mapper = new ObjectMapper();

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    Clock fixedClock() {
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
  void independentWebSocketsReadConsistentSnapshotAndReconnect() throws Exception {
    JsonNode fixture =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    boards.importBoard(
        LocalDate.parse("2026-11-07"), fixture.path("definition"), fixture.path("snapshot"));
    String origin = "http://127.0.0.1:" + port;
    URI sessionUri = URI.create(origin + "/api/v1/session");
    URI socketUri = URI.create("ws://127.0.0.1:" + port + "/api/v1/game/stream");
    HttpClient a = HttpClient.newHttpClient(), b = HttpClient.newHttpClient();
    String cookieA = cookie(a, sessionUri, origin), cookieB = cookie(b, sessionUri, origin);
    HttpResponse<String> current =
        a.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/game/current"))
                .header("Cookie", cookieA)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(mapper.readTree(current.body()).path("allowance").path("remaining").intValue())
        .isEqualTo(5);
    assertThrows(
        ExecutionException.class,
        () ->
            a.newWebSocketBuilder()
                .header("Origin", origin)
                .buildAsync(socketUri, new Listener())
                .get());
    assertThrows(
        ExecutionException.class,
        () ->
            a.newWebSocketBuilder()
                .header("Origin", "http://attacker.test")
                .header("Cookie", cookieA)
                .buildAsync(socketUri, new Listener())
                .get());
    Listener listenerA = new Listener(), listenerB = new Listener();
    WebSocket socketA =
        a.newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookieA)
            .buildAsync(socketUri, listenerA)
            .get();
    WebSocket socketB =
        b.newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookieB)
            .buildAsync(socketUri, listenerB)
            .get();
    String subscribe =
        "{\"schemaVersion\":1,\"type\":\"subscribe\",\"puzzleId\":\"00000000-0000-4000-8000-000000000100\",\"afterBoardVersion\":null}";
    socketA.sendText(subscribe.replace("null", "6"), true).get();
    assertThat(mapper.readTree(listenerA.next()).path("type").asText()).isEqualTo("resyncRequired");
    socketA
        .sendText(subscribe.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"), true)
        .get();
    assertThat(mapper.readTree(listenerA.next()).path("error").path("code").asText())
        .isEqualTo("INVALID_REQUEST");
    socketA
        .sendText(
            subscribe.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            true)
        .get();
    assertThat(mapper.readTree(listenerA.next()).path("error").path("code").asText())
        .isEqualTo("INVALID_REQUEST");
    socketA.sendText("{\"schemaVersion\":1,\"type\":\"preview.submit\"}", true).get();
    assertThat(mapper.readTree(listenerA.next()).path("error").path("code").asText())
        .isEqualTo("INVALID_REQUEST");
    socketA.sendText(subscribe, true).get();
    socketB.sendText(subscribe, true).get();
    JsonNode aSnapshot = mapper.readTree(listenerA.next()),
        bSnapshot = mapper.readTree(listenerB.next());
    assertThat(aSnapshot.path("type").asText()).isEqualTo("subscribed");
    assertThat(aSnapshot.path("snapshot")).isEqualTo(bSnapshot.path("snapshot"));
    assertThat(aSnapshot.path("snapshot")).isEqualTo(fixture.path("snapshot"));
    assertThat(aSnapshot.path("watermark").longValue())
        .isEqualTo(aSnapshot.path("snapshot").path("boardVersion").longValue());
    assertThat(listenerA.closed()).isEqualTo(1013);
    assertThat(listenerB.closed()).isEqualTo(1013);
    Listener reconnect = new Listener();
    WebSocket socketC =
        b.newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookieB)
            .buildAsync(socketUri, reconnect)
            .get();
    socketC.sendText(subscribe, true).get();
    assertThat(mapper.readTree(reconnect.next()).path("snapshot"))
        .isEqualTo(aSnapshot.path("snapshot"));
    assertThat(reconnect.closed()).isEqualTo(1013);
  }

  private String cookie(HttpClient client, URI uri, String origin) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(uri)
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
  }

  private static class Listener implements WebSocket.Listener {
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final java.util.concurrent.CompletableFuture<Integer> closed =
        new java.util.concurrent.CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();

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

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
      closed.complete(status);
      return null;
    }

    int closed() throws Exception {
      return closed.get(5, TimeUnit.SECONDS);
    }

    String next() throws InterruptedException {
      String message = messages.poll(5, TimeUnit.SECONDS);
      if (message == null) throw new AssertionError("WebSocket reply timed out");
      return message;
    }
  }
}
