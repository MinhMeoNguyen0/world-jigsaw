package com.worldjigsaw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
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
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);

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
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
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
