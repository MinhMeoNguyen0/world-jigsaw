package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@Import(ConfirmationJournalTest.ClockConfig.class)
class ConfirmationJournalTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  static final AtomicReference<Instant> NOW =
      new AtomicReference<>(Instant.parse("2026-11-07T23:59:59Z"));
  private final ObjectMapper mapper = new ObjectMapper();
  @Autowired GameBoards boards;
  @Autowired ConfirmationJournal journal;
  @Autowired BoardValidator validator;
  @Autowired DraftRules rules;
  @Autowired GameIdentityLedger identities;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired Clock clock;
  @Autowired GameWriterGuard writer;

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    Clock mutableClock() {
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
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  void commitsOnceAcrossRestartAndConflictsAndAdmitsBeforeUtcClose() throws Exception {
    JsonNode fixture =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    ObjectNode initial = fixture.path("snapshot").deepCopy();
    for (JsonNode group : initial.path("groups"))
      ((ObjectNode) group.path("transform")).put("rotation", 0);
    boards.importBoard(LocalDate.parse("2026-11-07"), fixture.path("definition"), initial);
    UUID puzzle = UUID.fromString(initial.path("puzzleId").textValue());
    assertReceiptReadersWaitForSettlement(puzzle, true);
    assertReceiptReadersWaitForSettlement(puzzle, false);
    UUID subject = UUID.randomUUID();
    UUID command = UUID.randomUUID();
    JsonNode payload = payload(puzzle, command, 1);
    ObjectNode firstTransform = initial.path("groups").get(0).path("transform").deepCopy();
    ((ObjectNode) firstTransform.path("translation")).put("x", -500);
    DraftRules.Draft draft =
        rules.move(
            rules.begin(fixture.path("definition"), initial, 5),
            initial.path("groups").get(0).path("groupId").textValue(),
            UUID.randomUUID().toString(),
            firstTransform);
    JsonNode result =
        journal.confirm(
            subject,
            puzzle,
            command,
            payload,
            (definition, latest, remaining) -> {
              DraftRules.Confirmation confirmed = rules.confirm(draft, latest, remaining);
              return new ConfirmationJournal.Plan(confirmed.snapshot(), confirmed.charged());
            });
    assertThat(result.path("outcome").asText()).isEqualTo("confirmed");
    assertThat(result.path("boardVersion").intValue()).isEqualTo(8);
    assertThat(result.path("allowance").path("used").intValue()).isEqualTo(1);
    assertThat(
            mapper.valueToTree(boards.currentFor(subject).get("allowance")).path("used").intValue())
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM game_events", Integer.class)).isEqualTo(1);

    UUID persistedTurn = identities.reserveTurn(subject, puzzle),
        reservedGroup = identities.reserveGroup(puzzle);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM game_turn_ids WHERE turn_id = ?",
                Integer.class,
                persistedTurn))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM game_group_ids WHERE puzzle_id = ? AND group_id = ?",
                Integer.class,
                puzzle,
                reservedGroup))
        .isEqualTo(1);
    UUID requestId = UUID.randomUUID();
    JsonNode request =
        mapper
            .createObjectNode()
            .put("puzzleId", puzzle.toString())
            .put("requestId", requestId.toString())
            .put("turnId", persistedTurn.toString());
    JsonNode cancelResult = mapper.createObjectNode().put("outcome", "canceled");
    assertThat(
            identities.record(subject, puzzle, requestId, "cancel", request, cancelResult).result())
        .isEqualTo(cancelResult);
    GameIdentityLedger reloadedIdentities = new GameIdentityLedger(jdbc, writer);
    var identityTx = new TransactionTemplate(transactions);
    assertThat(
            identityTx
                .execute(status ->
                    reloadedIdentities.record(
                        subject, puzzle, requestId, "cancel", request, mapper.nullNode()))
                .result())
        .isEqualTo(cancelResult);
    ObjectNode differentRequest = request.deepCopy();
    differentRequest.put("turnId", UUID.randomUUID().toString());
    assertThat(
            identities
                .record(subject, puzzle, requestId, "cancel", differentRequest, cancelResult)
                .conflict())
        .isTrue();
    assertThat(
            identities.record(subject, puzzle, requestId, "undo", request, cancelResult).conflict())
        .isTrue();

    // Recreate the service object and transaction to model a process that reloads persisted state.
    ConfirmationJournal restarted = new ConfirmationJournal(jdbc, validator, clock, writer);
    var tx = new TransactionTemplate(transactions);
    JsonNode retry =
        tx.execute(status ->
            restarted.confirm(
                subject,
                puzzle,
                command,
                payload,
                (d, s, remaining) -> {
                  throw new AssertionError("duplicate must not execute");
                }));
    assertThat(retry).isEqualTo(result);
    JsonNode conflict =
        journal.confirm(subject, puzzle, command, payload(puzzle, command, 2), move(2, 500));
    assertThat(conflict.path("error").path("code").asText()).isEqualTo("COMMAND_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT used FROM game_allowances WHERE subject_id = ?", Integer.class, subject))
        .isEqualTo(1);

    // Two independently identified contenders start from the same observed group revision.
    UUID contenderA = UUID.randomUUID(), contenderB = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(() -> {
            start.await();
            UUID id = UUID.randomUUID();
            return journal.confirm(contenderA, puzzle, id, payload(puzzle, id, 1), move(3, 100));
          });
      var b =
          pool.submit(() -> {
            start.await();
            UUID id = UUID.randomUUID();
            return journal.confirm(contenderB, puzzle, id, payload(puzzle, id, 1), move(3, 100));
          });
      start.countDown();
      assertThat(
              java.util.List.of(a.get().path("outcome").asText(), b.get().path("outcome").asText()))
          .containsExactlyInAnyOrder("confirmed", "rejected");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT board_version FROM game_boards WHERE puzzle_id = ?", Long.class, puzzle))
        .isEqualTo(9);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM game_events", Integer.class)).isEqualTo(2);

    // An event insert failure occurs after board replacement; the entire transaction rolls back.
    jdbc.execute(
        "CREATE FUNCTION fail_journal_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_journal_event BEFORE INSERT ON game_events FOR EACH ROW EXECUTE FUNCTION fail_journal_event()");
    UUID failure = UUID.randomUUID();
    assertThatThrownBy(() ->
            journal.confirm(subject, puzzle, failure, payload(puzzle, failure, 1), move(4, 100)))
        .isInstanceOf(RuntimeException.class);
    jdbc.execute("DROP TRIGGER fail_journal_event ON game_events");
    assertThat(
            jdbc.queryForObject(
                "SELECT board_version FROM game_boards WHERE puzzle_id = ?", Long.class, puzzle))
        .isEqualTo(9);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM game_commands WHERE command_id = ?", Integer.class, failure))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT used FROM game_allowances WHERE subject_id = ?", Integer.class, subject))
        .isEqualTo(1);

    UUID admitted = UUID.randomUUID();
    AtomicReference<Instant> admissionSample = new AtomicReference<>();
    JsonNode admittedResult =
        journal.confirm(
            subject,
            puzzle,
            admitted,
            payload(puzzle, admitted, 1),
            (definition, latest, remaining) -> {
              var plan = move(4, 100).apply(definition, latest, remaining);
              NOW.set(Instant.parse("2026-11-08T00:00:00Z"));
              return plan;
            },
            admissionSample::set);
    assertThat(admissionSample.get()).isEqualTo(Instant.parse("2026-11-07T23:59:59Z"));
    assertThat(admittedResult.path("outcome").asText()).isEqualTo("confirmed");
    assertThat(admittedResult.path("boardVersion").intValue()).isEqualTo(10);
    UUID closed = UUID.randomUUID();
    JsonNode closedResult =
        journal.confirm(
            subject,
            puzzle,
            closed,
            payload(puzzle, closed, 1),
            (d, s, remaining) -> {
              throw new AssertionError("closed puzzle admitted");
            });
    assertThat(closedResult.path("error").path("code").asText()).isEqualTo("PUZZLE_CLOSED");
    assertThat(journal.confirm(subject, puzzle, closed, payload(puzzle, closed, 1), move(5, 100)))
        .isEqualTo(closedResult);
    assertThat(
            jdbc.queryForObject(
                "SELECT used FROM game_allowances WHERE subject_id = ?", Integer.class, subject))
        .isEqualTo(2);

    // A request waiting on the board lock at midnight is admitted only after acquiring it.
    NOW.set(Instant.parse("2026-11-07T23:59:59Z"));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var holder =
          pool.submit(() ->
              tx.execute(status -> {
                jdbc.queryForObject(
                    "SELECT puzzle_id FROM game_boards WHERE puzzle_id = ? FOR UPDATE",
                    UUID.class,
                    puzzle);
                locked.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                return null;
              }));
      locked.await();
      UUID waitingCommand = UUID.randomUUID();
      var waiting =
          pool.submit(() ->
              journal.confirm(
                  UUID.randomUUID(),
                  puzzle,
                  waitingCommand,
                  payload(puzzle, waitingCommand, 1),
                  move(5, 100)));
      try {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        int waiters = 0;
        while (waiters == 0 && System.nanoTime() < deadline) {
          waiters =
              jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT definition%FOR UPDATE%'",
                  Integer.class);
          if (waiters == 0) Thread.sleep(20);
        }
        assertThat(waiters).isPositive();
        NOW.set(Instant.parse("2026-11-08T00:00:00Z"));
      } finally {
        release.countDown();
      }
      holder.get();
      assertThat(waiting.get().path("error").path("code").asText()).isEqualTo("PUZZLE_CLOSED");
    }

    // Concurrent retries of the same command execute the trusted planner exactly once.
    NOW.set(Instant.parse("2026-11-07T23:59:59Z"));
    UUID duplicateSubject = UUID.randomUUID(), duplicateCommand = UUID.randomUUID();
    JsonNode duplicatePayload = payload(puzzle, duplicateCommand, 1);
    var executions = new java.util.concurrent.atomic.AtomicInteger();
    var duplicateStart = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<JsonNode> call =
          () -> {
            duplicateStart.await();
            return journal.confirm(
                duplicateSubject,
                puzzle,
                duplicateCommand,
                duplicatePayload,
                (d, s, remaining) -> {
                  executions.incrementAndGet();
                  return move(5, 100).apply(d, s, remaining);
                });
          };
      var a = pool.submit(call);
      var b = pool.submit(call);
      duplicateStart.countDown();
      assertThat(a.get()).isEqualTo(b.get());
      assertThat(executions).hasValue(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT used FROM game_allowances WHERE subject_id = ?",
                Integer.class,
                duplicateSubject))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT board_version FROM game_boards WHERE puzzle_id = ?", Long.class, puzzle))
        .isEqualTo(11);

    // A candidate with a deep overlap is rejected without changing the board or charging quota.
    UUID collisionCommand = UUID.randomUUID();
    JsonNode collision =
        journal.confirm(
            subject,
            puzzle,
            collisionCommand,
            payload(puzzle, collisionCommand, 1),
            (d, latest, remaining) -> {
              ObjectNode proposed = latest.deepCopy();
              ObjectNode group = (ObjectNode) proposed.path("groups").get(0);
              ((ObjectNode) group.path("transform").path("translation"))
                  .put("x", 5000)
                  .put("y", -2000);
              group.put("revision", group.path("revision").longValue() + 1);
              proposed.put("boardVersion", latest.path("boardVersion").longValue() + 1);
              return new ConfirmationJournal.Plan(proposed, 1);
            });
    assertThat(collision.path("error").path("message").asText())
        .isEqualTo("Forbidden piece overlap");
    assertThat(
            jdbc.queryForObject(
                "SELECT used FROM game_allowances WHERE subject_id = ?", Integer.class, subject))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT board_version FROM game_boards WHERE puzzle_id = ?", Long.class, puzzle))
        .isEqualTo(11);

    // Imported arbitrary-angle groups are never rewritten when interaction is refused.
    jdbc.update(
        "UPDATE game_groups SET rotation = 31500 WHERE group_id = ?::uuid",
        "00000000-0000-4000-8000-000000000201");
    JsonNode beforeLegacy = mapper.valueToTree(boards.current().get("snapshot"));
    UUID legacyCommand = UUID.randomUUID();
    JsonNode legacyResult =
        journal.confirm(
            subject,
            puzzle,
            legacyCommand,
            payload(puzzle, legacyCommand, 1),
            (d, s, remaining) -> {
              throw new AssertionError("legacy board mutated");
            });
    assertThat(legacyResult.path("error").path("message").asText())
        .isEqualTo("UNSUPPORTED_LEGACY_ORIENTATION");
    assertThat((JsonNode) mapper.valueToTree(boards.current().get("snapshot")))
        .isEqualTo(beforeLegacy);
  }

  private void assertReceiptReadersWaitForSettlement(UUID puzzle, boolean commit) throws Exception {
    UUID subject = UUID.randomUUID(), command = UUID.randomUUID(), turn = UUID.randomUUID();
    jdbc.update("INSERT INTO game_subjects(subject_id) VALUES (?)", subject);
    JsonNode commandPayload = mapper.createObjectNode().put("puzzleId", puzzle.toString())
        .put("commandId", command.toString()).put("turnId", turn.toString());
    String canonicalCommand = new org.erdtman.jcs.JsonCanonicalizer(commandPayload.toString()).getEncodedString();
    JsonNode commandResult = mapper.createObjectNode().put("outcome", "confirmed");
    java.util.List<String> kinds = java.util.List.of("begin", "cancel", "undo");
    java.util.List<UUID> requests = kinds.stream().map(ignored -> UUID.randomUUID()).toList();
    java.util.List<JsonNode> payloads = requests.stream().map(request -> (JsonNode) mapper.createObjectNode()
        .put("puzzleId", puzzle.toString()).put("turnId", turn.toString()).put("requestId", request.toString())).toList();
    java.util.List<String> canonicals = new java.util.ArrayList<>();
    for (JsonNode payload : payloads)
      canonicals.add(new org.erdtman.jcs.JsonCanonicalizer(payload.toString()).getEncodedString());
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ready = new CountDownLatch(6);
    try (var pool = Executors.newFixedThreadPool(7)) {
      var writer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
        jdbc.queryForObject("SELECT subject_id FROM game_subjects WHERE subject_id = ? FOR UPDATE", UUID.class, subject);
        jdbc.update("INSERT INTO game_commands(subject_id, command_id, puzzle_id, canonical_payload, result) VALUES (?, ?, ?, ?, ?::jsonb)",
            subject, command, puzzle, canonicalCommand, commandResult.toString());
        for (int i = 0; i < kinds.size(); i++)
          jdbc.update("INSERT INTO game_requests(subject_id, request_id, puzzle_id, request_kind, canonical_payload, result) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
              subject, requests.get(i), puzzle, kinds.get(i), canonicals.get(i), commandResult.toString());
        locked.countDown();
        try {
          release.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ex);
        }
        if (!commit) status.setRollbackOnly();
        return null;
      }));
      locked.await();
      java.util.List<java.util.concurrent.Future<JsonNode>> readers = new java.util.ArrayList<>();
      readers.add(pool.submit(() -> { ready.countDown(); return journal.lookup(subject, puzzle, command, commandPayload); }));
      readers.add(pool.submit(() -> { ready.countDown(); return journal.find(subject, command); }));
      readers.add(pool.submit(() -> { ready.countDown(); return journal.confirmedForTurn(subject, turn); }));
      for (int i = 0; i < kinds.size(); i++) {
        int index = i;
        readers.add(pool.submit(() -> {
          ready.countDown();
          var receipt = identities.lookup(subject, requests.get(index), kinds.get(index), payloads.get(index));
          return receipt == null ? null : receipt.result();
        }));
      }
      try {
        assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        int waiting = 0;
        while (waiting < readers.size() && System.nanoTime() < deadline) {
          waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT subject_id FROM game_subjects%FOR UPDATE%'", Integer.class);
          if (waiting < readers.size()) Thread.sleep(20);
        }
        assertThat(waiting).isGreaterThanOrEqualTo(readers.size());
        assertThatThrownBy(() -> journal.findForRecovery(subject, command))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
      } finally {
        release.countDown();
      }
      writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
      for (var reader : readers)
        assertThat(reader.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(commit ? commandResult : null);
    }
  }

  private JsonNode payload(UUID puzzle, UUID command, int marker) {
    return mapper
        .createObjectNode()
        .put("puzzleId", puzzle.toString())
        .put("commandId", command.toString())
        .put("marker", marker);
  }

  private ConfirmationJournal.Planner move(long expectedRevision, int delta) {
    return (definition, latest, remaining) -> {
      ObjectNode snapshot = latest.deepCopy();
      ObjectNode group = (ObjectNode) snapshot.path("groups").get(0);
      if (group.path("revision").longValue() != expectedRevision)
        throw new ConfirmationJournal.Rejected("STALE_REVISION", "Group changed");
      if (remaining == 0)
        throw new ConfirmationJournal.Rejected("QUOTA_EXCEEDED", "No moves remain");
      ObjectNode translation = (ObjectNode) group.path("transform").path("translation");
      translation.put("x", translation.path("x").intValue() + delta);
      group.put("revision", expectedRevision + 1);
      snapshot.put("boardVersion", latest.path("boardVersion").longValue() + 1);
      return new ConfirmationJournal.Plan(snapshot, 1);
    };
  }
}
