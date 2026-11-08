package com.worldjigsaw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.worldjigsaw.game.GameBoards;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.erdtman.jcs.JsonCanonicalizer;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(GameBoardsTest.ClockConfig.class)
class GameBoardsTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  static final AtomicReference<Instant> NOW =
      new AtomicReference<>(Instant.parse("2026-11-07T23:59:59Z"));

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    Clock testClock() {
      return new Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
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
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired GameBoards boards;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @LocalServerPort int port;

  @Test
  void importsAndReadsFixtureAtomicallyAcrossUtcBoundary() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode fixture =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    JsonNode definition = fixture.path("definition"), snapshot = fixture.path("snapshot");
    LocalDate day = LocalDate.parse("2026-11-07");

    ObjectNode invalid = snapshot.deepCopy();
    ((ObjectNode) invalid.path("groups").get(1))
        .set("pieceIds", snapshot.path("groups").get(0).path("pieceIds"));
    assertThrows(
        IllegalArgumentException.class, () -> boards.importBoard(day, definition, invalid));
    ObjectNode tampered = definition.deepCopy();
    tampered.put("image", "changed");
    assertThrows(IllegalArgumentException.class, () -> boards.importBoard(day, tampered, snapshot));
    ObjectNode malformed = definition.deepCopy();
    malformed.put("unexpected", true);
    assertThrows(
        IllegalArgumentException.class, () -> boards.importBoard(day, malformed, snapshot));
    ObjectNode wrongGeometry = definition.deepCopy();
    ((ObjectNode) wrongGeometry.path("pieces").get(1).path("solvedPosition")).put("x", 999);
    ObjectNode noChecksum = wrongGeometry.deepCopy();
    noChecksum.remove("checksum");
    byte[] canonical =
        new JsonCanonicalizer(mapper.writeValueAsString(noChecksum)).getEncodedUTF8();
    wrongGeometry.put(
        "checksum",
        "sha256:"
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)));
    assertThrows(
        IllegalArgumentException.class, () -> boards.importBoard(day, wrongGeometry, snapshot));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM puzzles", Integer.class)).isZero();

    jdbc.update(
        "INSERT INTO puzzles(id, puzzle_day) VALUES (?::uuid, ?)",
        definition.path("puzzleId").textValue(),
        day);
    boards.importBoard(day, definition, snapshot);
    assertThrows(Exception.class, () -> boards.importBoard(day, definition, snapshot));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM game_group_pieces", Integer.class))
        .isEqualTo(3);
    assertThrows(
        Exception.class,
        () ->
            jdbc.update(
                "DELETE FROM game_group_pieces WHERE piece_id = ?::uuid",
                "00000000-0000-4000-8000-000000000003"));
    assertThrows(
        Exception.class, () -> jdbc.update("UPDATE game_boards SET definition = '{}'::jsonb"));

    RestClient client = RestClient.create("http://localhost:" + port);
    Map<?, ?> current = client.get().uri("/api/v1/game/current").retrieve().body(Map.class);
    assertThat(current.get("day")).isEqualTo("2026-11-07");
    assertThat(current.get("serverTime")).isEqualTo("2026-11-07T23:59:59Z");
    assertThat(current.get("endsAt")).isEqualTo("2026-11-08T00:00:00Z");
    assertThat((JsonNode) mapper.valueToTree(current.get("definition"))).isEqualTo(definition);
    assertThat((JsonNode) mapper.valueToTree(current.get("snapshot"))).isEqualTo(snapshot);
    var response = client.get().uri("/api/v1/game/snapshot").retrieve().toEntity(Map.class);
    assertThat(response.getHeaders().getFirst("X-Server-Time")).isEqualTo("2026-11-07T23:59:59Z");
    assertThat((JsonNode) mapper.valueToTree(response.getBody())).isEqualTo(snapshot);

    // Each writer transaction changes the version and transform together. Concurrent readers
    // must never observe one without the other, even between the read service's two queries.
    try (var pool = Executors.newSingleThreadExecutor()) {
      var writer =
          pool.submit(() -> {
            var tx = new TransactionTemplate(transactions);
            for (int version = 8; version < 48; version++) {
              int next = version;
              tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE game_boards SET board_version = ?", next);
                jdbc.update(
                    "UPDATE game_groups SET translation_x = ? WHERE group_id = ?::uuid",
                    next,
                    "00000000-0000-4000-8000-000000000201");
              });
            }
          });
      for (int i = 0; i < 100; i++) {
        JsonNode read = mapper.valueToTree(boards.current().get("snapshot"));
        int version = read.path("boardVersion").intValue();
        JsonNode group = read.path("groups").get(0);
        assertThat(group.path("transform").path("translation").path("x").intValue())
            .isEqualTo(version == 7 ? -1000 : version);
      }
      writer.get();
    }

    NOW.set(Instant.parse("2026-11-08T00:00:00Z"));
    var unavailable =
        client
            .get()
            .uri("/api/v1/game/current")
            .exchange((req, res) -> {
              assertThat(res.getStatusCode().value()).isEqualTo(503);
              return mapper.readTree(res.getBody());
            });
    assertThat(unavailable.path("error").path("code").asText()).isEqualTo("PUZZLE_UNAVAILABLE");
  }
}
