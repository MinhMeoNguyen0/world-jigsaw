package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class GameWriterFenceProcessTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void successorWaitsForProcessAndOutstandingTransactions() throws Exception {
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(postgres.getJdbcUrl());
    source.setUsername(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(source);
    JsonNode board = mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    UUID puzzle = UUID.fromString(board.path("snapshot").path("puzzleId").asText());
    UUID group = UUID.fromString(board.path("snapshot").path("groups").get(0).path("groupId").asText());
    GameWriterGuard setup = new GameWriterGuard(source);
    try {
      var boards = new GameBoards(jdbc, new BoardValidator(), Clock.systemUTC(), setup);
      new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status ->
          boards.importBoard(LocalDate.now(ZoneOffset.UTC), board.path("definition"), board.path("snapshot")));
    } finally {
      setup.close();
    }
    Path directory = Files.createTempDirectory(Path.of("."), ".writer-probe-").toAbsolutePath();
    Process first = null, successor = null;
    var readerPool = Executors.newCachedThreadPool();
    try (Connection transaction = source.getConnection()) {
      first = start(directory, source);
      BufferedReader firstOutput = new BufferedReader(new InputStreamReader(first.getInputStream()));
      BufferedWriter firstInput = new BufferedWriter(new OutputStreamWriter(first.getOutputStream()));
      assertThat(line(readerPool, firstOutput)).isEqualTo("READY");
      UUID subject = UUID.randomUUID(), command = UUID.randomUUID(), turn = UUID.randomUUID();
      String request = puzzle + " " + subject + " " + command + " " + group + " " + turn;
      send(firstInput, "CONFIRM " + request);
      assertThat(line(readerPool, firstOutput)).isEqualTo("PLANNING");
      transaction.setAutoCommit(false);
      try (var statement = transaction.prepareStatement("SELECT pg_advisory_xact_lock_shared(?)")) {
        statement.setLong(1, 0x574a494744524149L);
        statement.execute();
      }
      long key = 0x574a494753415645L;
      Integer owner = jdbc.queryForObject(
          "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND classid::bigint = ? AND objid::bigint = ? AND granted",
          Integer.class, key >>> 32, key & 0xffffffffL);
      assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, owner)).isTrue();
      Process blocked = start(directory, source);
      assertThat(blocked.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(blocked.exitValue()).isNotZero();
      send(firstInput, "CONTINUE");
      JsonNode committed = result(line(readerPool, firstOutput));
      assertThat(committed.path("outcome").asText()).isEqualTo("confirmed");
      assertThat(committed.path("allowance").path("used").asInt()).isEqualTo(1);
      first.destroyForcibly();
      assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
      successor = start(directory, source);
      BufferedReader nextOutput = new BufferedReader(new InputStreamReader(successor.getInputStream()));
      BufferedWriter nextInput = new BufferedWriter(new OutputStreamWriter(successor.getOutputStream()));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      int waiters = 0;
      while (waiters == 0 && System.nanoTime() < deadline) {
        waiters = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT pg_advisory_xact_lock(%'", Integer.class);
        if (waiters == 0) Thread.sleep(20);
      }
      assertThat(waiters).isPositive();
      assertThat(nextOutput.ready()).isFalse();
      transaction.commit();
      assertThat(line(readerPool, nextOutput)).isEqualTo("READY");
      send(nextInput, "RETRY " + request);
      assertThat(result(line(readerPool, nextOutput))).isEqualTo(committed);
      assertThat(jdbc.queryForObject("SELECT used FROM game_allowances WHERE subject_id = ? AND puzzle_id = ?", Integer.class, subject, puzzle)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM game_commands WHERE subject_id = ? AND command_id = ?", Integer.class, subject, command)).isEqualTo(1);
      send(nextInput, "EXIT");
      assertThat(successor.waitFor(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (first != null && first.isAlive()) first.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      if (successor != null && successor.isAlive()) successor.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      readerPool.shutdownNow();
      Files.deleteIfExists(directory.resolve(".world-jigsaw-writer.lock"));
      Files.deleteIfExists(directory);
    }
  }

  private Process start(Path directory, DriverManagerDataSource source) throws Exception {
    return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("worldjigsaw.test.classpath"), GameWriterProcess.class.getName(),
        source.getUrl(), source.getUsername(), source.getPassword())
        .directory(directory.toFile()).redirectErrorStream(true).start();
  }

  private String line(java.util.concurrent.ExecutorService pool, BufferedReader reader) throws Exception {
    return pool.submit(() -> {
      String value;
      while ((value = reader.readLine()) != null)
        if (value.equals("READY") || value.equals("PLANNING") || value.equals("FAILED")
            || value.startsWith("RESULT ")) return value;
      return null;
    }).get(20, TimeUnit.SECONDS);
  }

  private JsonNode result(String line) throws Exception {
    assertThat(line).startsWith("RESULT ");
    return mapper.readTree(line.substring("RESULT ".length()));
  }

  private void send(BufferedWriter writer, String text) throws Exception {
    writer.write(text);
    writer.newLine();
    writer.flush();
  }
}
