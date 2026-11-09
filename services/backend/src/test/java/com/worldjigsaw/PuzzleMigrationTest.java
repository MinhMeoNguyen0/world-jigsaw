package com.worldjigsaw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises Flyway and puzzle identity constraints against PostgreSQL. */
@Testcontainers
class PuzzleMigrationTest {

  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

  @Test
  void freshMigrationEnforcesUniqueIdentityAndUtcDay() throws SQLException {
    String schema = schemaName();
    Flyway flyway = flyway(schema, null);
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    UUID first = UUID.randomUUID();
    LocalDate day = LocalDate.of(2026, 11, 7);
    try (Connection connection = connection(schema)) {
      insert(connection, first, day);
      insert(connection, UUID.randomUUID(), day.plusDays(1));

      SQLException duplicateDay =
          assertThrows(SQLException.class, () -> insert(connection, UUID.randomUUID(), day));
      assertThat(duplicateDay.getSQLState()).isEqualTo("23505");
      SQLException duplicateId =
          assertThrows(SQLException.class, () -> insert(connection, first, day.plusDays(2)));
      assertThat(duplicateId.getSQLState()).isEqualTo("23505");
      SQLException missingDay =
          assertThrows(SQLException.class, () -> insert(connection, UUID.randomUUID(), null));
      assertThat(missingDay.getSQLState()).isEqualTo("23502");
      SQLException missingId =
          assertThrows(SQLException.class, () -> insert(connection, null, day.plusDays(3)));
      assertThat(missingId.getSQLState()).isEqualTo("23502");

      try (var statement = connection.createStatement()) {
        statement.execute("SET TIME ZONE 'Pacific/Auckland'");
        try (ResultSet result =
            statement.executeQuery("SELECT id, puzzle_day FROM puzzles ORDER BY puzzle_day")) {
          assertThat(result.next()).isTrue();
          assertThat(result.getObject("id", UUID.class)).isEqualTo(first);
          assertThat(result.getObject("puzzle_day", LocalDate.class)).isEqualTo(day);
          assertThat(result.next()).isTrue();
          assertThat(result.getObject("puzzle_day", LocalDate.class)).isEqualTo(day.plusDays(1));
          assertThat(result.next()).isFalse();
        }
      }
    }
  }

  @Test
  void upgradesExistingBaselineAndPreservesPuzzleOnRerun() throws SQLException {
    String schema = schemaName();
    assertThat(flyway(schema, "1").migrate().migrationsExecuted).isEqualTo(1);
    Flyway latest = flyway(schema, null);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);

    UUID id = UUID.randomUUID();
    LocalDate day = LocalDate.of(2026, 11, 8);
    try (Connection connection = connection(schema)) {
      insert(connection, id, day);
    }
    assertThat(latest.migrate().migrationsExecuted).isZero();
    try (Connection connection = connection(schema);
        PreparedStatement query =
            connection.prepareStatement("SELECT puzzle_day FROM puzzles WHERE id = ?")) {
      query.setObject(1, id);
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getObject(1, LocalDate.class)).isEqualTo(day);
        assertThat(result.next()).isFalse();
      }
    }
  }

  @Test
  void upgradesIdentitySchemaWithExistingPuzzle() throws SQLException {
    String schema = schemaName();
    assertThat(flyway(schema, "2").migrate().migrationsExecuted).isEqualTo(2);
    UUID id = UUID.randomUUID();
    LocalDate day = LocalDate.of(2026, 11, 8);
    try (Connection connection = connection(schema)) {
      insert(connection, id, day);
    }
    Flyway latest = flyway(schema, null);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
    assertThat(latest.migrate().migrationsExecuted).isZero();
    try (Connection connection = connection(schema);
        PreparedStatement query =
            connection.prepareStatement("SELECT puzzle_day FROM puzzles WHERE id = ?")) {
      query.setObject(1, id);
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getObject(1, LocalDate.class)).isEqualTo(day);
      }
    }
  }

  @Test
  void upgradesExistingArbitraryAngleBoardWithoutChangingIt() throws Exception {
    String schema = schemaName();
    assertThat(flyway(schema, "3").migrate().migrationsExecuted).isEqualTo(3);
    JsonNode fixture =
        new ObjectMapper()
            .readTree(
                Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    UUID id = UUID.fromString(fixture.path("definition").path("puzzleId").textValue());
    String definition = fixture.path("definition").toString();
    String snapshot = fixture.path("snapshot").toString();
    String before;
    try (Connection connection = connection(schema)) {
      connection.setAutoCommit(false);
      insert(connection, id, LocalDate.of(2026, 11, 7));
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO game_boards(puzzle_id, definition_version, definition, board_version, closes_at) VALUES (?, 1, ?::jsonb, 7, '2026-11-08T00:00:00Z')")) {
        statement.setObject(1, id);
        statement.setString(2, definition);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO game_pieces(puzzle_id, piece_id) SELECT ?, (p->>'pieceId')::uuid FROM jsonb_array_elements(?::jsonb->'pieces') p")) {
        statement.setObject(1, id);
        statement.setString(2, definition);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO game_groups(puzzle_id, group_id, revision, translation_x, translation_y, rotation, final_position) SELECT ?, (g->>'groupId')::uuid, (g->>'revision')::bigint, (g->'transform'->'translation'->>'x')::int, (g->'transform'->'translation'->>'y')::int, (g->'transform'->>'rotation')::int, (g->>'finalPosition')::boolean FROM jsonb_array_elements(?::jsonb->'groups') g")) {
        statement.setObject(1, id);
        statement.setString(2, snapshot);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO game_group_pieces(puzzle_id, group_id, piece_id) SELECT ?, (g->>'groupId')::uuid, p::uuid FROM jsonb_array_elements(?::jsonb->'groups') g CROSS JOIN LATERAL jsonb_array_elements_text(g->'pieceIds') p")) {
        statement.setObject(1, id);
        statement.setString(2, snapshot);
        statement.executeUpdate();
      }
      connection.commit();
      before = boardData(connection);
    }
    assertThat(flyway(schema, null).migrate().migrationsExecuted).isEqualTo(5);
    try (Connection connection = connection(schema)) {
      assertThat(boardData(connection)).isEqualTo(before);
      try (var statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT count(*) FROM game_group_ids")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
      }
      try (var statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery("SELECT rotation FROM game_groups ORDER BY rotation")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(15000);
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(31500);
      }
    }
  }

  private static String boardData(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT jsonb_build_object('board', (SELECT to_jsonb(b) FROM game_boards b), 'groups', (SELECT jsonb_agg(to_jsonb(g) ORDER BY g.group_id) FROM game_groups g), 'membership', (SELECT jsonb_agg(to_jsonb(m) ORDER BY m.piece_id) FROM game_group_pieces m), 'pieces', (SELECT jsonb_agg(to_jsonb(p) ORDER BY p.piece_id) FROM game_pieces p))::text")) {
      result.next();
      return result.getString(1);
    }
  }

  private static Flyway flyway(String schema, String target) {
    var config =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema);
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }

  private static Connection connection(String schema) throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setSchema(schema);
    return connection;
  }

  private static void insert(Connection connection, UUID id, LocalDate day) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO puzzles (id, puzzle_day) VALUES (?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, day);
      statement.executeUpdate();
    }
  }

  private static String schemaName() {
    return "test_" + UUID.randomUUID().toString().replace("-", "");
  }
}
