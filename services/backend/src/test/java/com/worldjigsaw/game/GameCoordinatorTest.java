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
import java.util.List;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@Import(GameCoordinatorTest.ClockConfig.class)
class GameCoordinatorTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  @Autowired GameBoards boards;
  @Autowired GameCoordinator game;
  @Autowired JdbcTemplate jdbc;
  @Autowired DraftRules rules;
  @Autowired GameIdentityLedger identities;
  @Autowired ConfirmationJournal journal;
  @Autowired Clock clock;
  @Autowired GameWriterGuard writer;
  @Autowired TurnLeaseRegistry leases;
  @Autowired BoardValidator validator;
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
  void independentTurnsPreviewCancelUndoAndJoinWithoutWritingCanonicalBoard() throws Exception {
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode definition = board.path("definition"), snapshot = board.path("snapshot");
    boards.importBoard(LocalDate.of(2026, 11, 7), definition, snapshot);
    UUID puzzle = UUID.fromString(snapshot.path("puzzleId").textValue()),
        a = UUID.randomUUID(),
        b = UUID.randomUUID();
    jdbc.update("INSERT INTO game_subjects(subject_id) VALUES (?), (?)", a, b);
    recoveryAndConnectedJoins(puzzle, snapshot);
    var slow = new java.util.concurrent.atomic.AtomicBoolean();
    game.subscribe(
        UUID.randomUUID(),
        a,
        puzzle,
        null,
        new GameCoordinator.Sink() {
          int calls;

          @Override
          public boolean offer(JsonNode value) {
            return ++calls == 1;
          }

          @Override
          public void slow() {
            slow.set(true);
          }
        });
    var first = game.begin(a, puzzle);
    var second = game.begin(b, puzzle);
    JsonNode g0 = snapshot.path("groups").get(0), g1 = snapshot.path("groups").get(1);
    UUID actionA = UUID.randomUUID(), actionB = UUID.randomUUID();
    var a0 = action(actionA, g0, 0, 0);
    var p1 = game.preview(a, first.turnId(), 0, 1, a0);
    assertThat(p1.pending()).isEqualTo(1);
    assertThat(slow).isTrue();
    assertThat(game.subscribe(puzzle).previews()).hasSize(1);
    assertSameSnapshot(game.subscribe(puzzle).snapshot(), snapshot);
    assertThatThrownBy(() -> game.preview(b, second.turnId(), 0, 1, action(actionB, g0, 0, 0)))
        .isInstanceOf(TurnLeaseRegistry.LeaseError.class)
        .hasMessageContaining("busy");
    var b1 = action(actionB, g1, 0, 0);
    assertThat(game.preview(b, second.turnId(), 0, 2, b1).pending()).isEqualTo(1);
    assertThat(game.subscribe(puzzle).previews()).hasSize(2);
    JsonNode lateConfirm = confirmPayload(puzzle, first.turnId(), 0, UUID.randomUUID());
    JsonNode cancelA = cancelPayload(puzzle, first.turnId(), 0, 0, UUID.randomUUID());
    JsonNode canceled = game.cancel(a, cancelA);
    assertThat(canceled.path("outcome").asText()).isEqualTo("canceled");
    assertThat(game.cancel(a, cancelA)).isEqualTo(canceled);
    JsonNode rejected = game.confirm(a, lateConfirm);
    assertThat(rejected.path("outcome").asText()).isEqualTo("rejected");
    assertThat(rejected.path("error").path("code").asText()).isEqualTo("TURN_CLOSED");
    assertThat(game.confirm(a, lateConfirm)).isEqualTo(rejected);
    ObjectNode changedCancel = cancelA.deepCopy();
    changedCancel.put("turnRevision", 1);
    assertThatThrownBy(() -> game.cancel(a, changedCancel))
        .isInstanceOf(DraftRules.RuleError.class)
        .hasMessageContaining("already used");
    assertSameSnapshot(game.subscribe(puzzle).snapshot(), snapshot);
    assertThat(game.subscribe(puzzle).previews()).hasSize(1);
    assertThatThrownBy(() -> game.preview(a, first.turnId(), 0, 3, a0))
        .isInstanceOf(DraftRules.RuleError.class);
    JsonNode undoB = undoPayload(puzzle, second.turnId(), 0, 0, 1, actionB, UUID.randomUUID());
    JsonNode undone = game.undo(b, undoB);
    assertThat(undone.path("generation").longValue()).isEqualTo(1);
    assertThat(game.undo(b, undoB)).isEqualTo(undone);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    assertThatThrownBy(() -> game.preview(b, second.turnId(), 0, 4, b1))
        .isInstanceOf(DraftRules.RuleError.class);
    assertThatThrownBy(() -> game.preview(b, second.turnId(), 1, 1, b1))
        .isInstanceOf(DraftRules.RuleError.class)
        .hasMessageContaining("already used");
    UUID joinedA = UUID.randomUUID(), joinedB = UUID.randomUUID();
    assertThat(game.preview(b, second.turnId(), 1, 2, action(joinedA, g0, 0, 0)).pending())
        .isEqualTo(1);
    assertThat(game.preview(b, second.turnId(), 1, 3, action(joinedB, g1, 0, 0)).pending())
        .isEqualTo(2);
    var merge =
        new PreviewGeneration.Action(
            joinedA,
            "piece",
            piece(g0),
            List.of(piece(g0), piece(g1)),
            List.of(base(g0), base(g1)),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    var joined = game.preview(b, second.turnId(), 1, 4, merge);
    assertThat(joined.pending()).isEqualTo(2);
    assertThat(joined.previews()).hasSize(2);
    assertThat(joined.previews().get(0).action().affectedPieceIds())
        .containsExactlyInAnyOrder(piece(g0), piece(g1));
    assertThat(joined.previews().get(1).action().targetId())
        .isEqualTo(joined.previews().get(0).action().targetId());
    assertThatThrownBy(() ->
            game.undo(b, undoPayload(puzzle, second.turnId(), 1, 1, 5, joinedA, UUID.randomUUID())))
        .isInstanceOf(DraftRules.RuleError.class)
        .hasMessageContaining("Joined");
    assertThat(
            game.cancel(b, cancelPayload(puzzle, second.turnId(), 1, 1, UUID.randomUUID()))
                .path("outcome")
                .asText())
        .isEqualTo("canceled");
    assertSameSnapshot(game.subscribe(puzzle).snapshot(), snapshot);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT board_version FROM game_boards WHERE puzzle_id = ?", Long.class, puzzle))
        .isEqualTo(snapshot.path("boardVersion").longValue());

    // Confirm a joined draft, then move the resulting connected group as one move.
    var savedTurn = game.begin(a, puzzle);
    UUID saveA = UUID.randomUUID(), saveB = UUID.randomUUID();
    game.preview(a, savedTurn.turnId(), 0, 1, action(saveA, g0, 0, 0));
    game.preview(a, savedTurn.turnId(), 0, 2, action(saveB, g1, 0, 0));
    game.preview(
        a,
        savedTurn.turnId(),
        0,
        3,
        new PreviewGeneration.Action(
            saveA,
            "piece",
            piece(g0),
            List.of(piece(g0), piece(g1)),
            List.of(base(g0), base(g1)),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0)));
    UUID saveCommand = UUID.randomUUID();
    JsonNode payload = confirmPayload(puzzle, savedTurn.turnId(), 0, saveCommand);
    JsonNode result = game.confirm(a, payload);
    assertThat(result.path("outcome").asText()).isEqualTo("confirmed");
    assertThat(result.path("allowance").path("used").intValue()).isEqualTo(2);
    assertThat(result.path("boardVersion").longValue())
        .isEqualTo(snapshot.path("boardVersion").longValue() + 1);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    var savedEvents = game.drainEvents().values();
    assertThat(savedEvents)
        .extracting(e -> e.path("type").asText())
        .containsExactly("preview", "preview", "boardChanged", "previewCleared");
    assertThat(game.confirm(a, payload)).isEqualTo(result);
    ObjectNode conflicting = payload.deepCopy();
    conflicting.put("turnRevision", 1);
    assertThat(game.confirm(a, conflicting).path("error").path("code").asText())
        .isEqualTo("COMMAND_CONFLICT");
    GameCoordinator restarted =
        new GameCoordinator(
            boards, rules, new TurnLeaseRegistry(clock), identities, journal, jdbc, clock, writer);
    assertThat(restarted.confirm(a, payload)).isEqualTo(result);
    JsonNode cancelAfterConfirm =
        cancelPayload(puzzle, savedTurn.turnId(), 0, 0, UUID.randomUUID());
    JsonNode confirmWon = game.cancel(a, cancelAfterConfirm);
    assertThat(confirmWon.path("outcome").asText()).isEqualTo("confirmed");
    assertThat(confirmWon.path("commandId").asText()).isEqualTo(saveCommand.toString());
    assertThat(restarted.cancel(a, cancelAfterConfirm)).isEqualTo(confirmWon);

    JsonNode afterSave = game.subscribe(puzzle).snapshot();
    JsonNode connected = null;
    for (JsonNode group : afterSave.path("groups"))
      if (group.path("pieceIds").size() == 2) connected = group;
    assertThat(connected).isNotNull();
    var groupTurn = game.begin(b, puzzle);
    UUID groupAction = UUID.randomUUID();
    java.util.List<UUID> groupPieces = new java.util.ArrayList<>();
    for (JsonNode p : connected.path("pieceIds")) groupPieces.add(UUID.fromString(p.textValue()));
    var groupMove =
        new PreviewGeneration.Action(
            groupAction,
            "group",
            UUID.fromString(connected.path("groupId").textValue()),
            groupPieces,
            List.of(base(connected)),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(1000, 0), 0));
    assertThat(game.preview(b, groupTurn.turnId(), 0, 1, groupMove).pending()).isEqualTo(1);
    JsonNode groupResult =
        game.confirm(b, confirmPayload(puzzle, groupTurn.turnId(), 0, UUID.randomUUID()));
    assertThat(groupResult.path("outcome").asText()).isEqualTo("confirmed");
    assertThat(groupResult.path("allowance").path("used").intValue()).isEqualTo(1);
    assertThat(groupResult.path("boardVersion").longValue())
        .isEqualTo(snapshot.path("boardVersion").longValue() + 2);

    JsonNode current = game.subscribe(puzzle).snapshot(), racingGroup = null;
    for (JsonNode group : current.path("groups"))
      if (group.path("pieceIds").size() == 2) racingGroup = group;
    assertThat(racingGroup).isNotNull();
    var raceTurn = game.begin(a, puzzle);
    UUID raceAction = UUID.randomUUID();
    java.util.List<UUID> racePieces = new java.util.ArrayList<>();
    for (JsonNode p : racingGroup.path("pieceIds")) racePieces.add(UUID.fromString(p.textValue()));
    var raceMove =
        new PreviewGeneration.Action(
            raceAction,
            "group",
            UUID.fromString(racingGroup.path("groupId").textValue()),
            racePieces,
            List.of(base(racingGroup)),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(2000, 0), 0));
    game.preview(a, raceTurn.turnId(), 0, 1, raceMove);
    JsonNode raceConfirm = confirmPayload(puzzle, raceTurn.turnId(), 0, UUID.randomUUID());
    JsonNode raceCancel = cancelPayload(puzzle, raceTurn.turnId(), 0, 0, UUID.randomUUID());
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var confirming =
          pool.submit(() -> {
            start.await();
            return game.confirm(a, raceConfirm);
          });
      var canceling =
          pool.submit(() -> {
            start.await();
            return game.cancel(a, raceCancel);
          });
      start.countDown();
      JsonNode outcome = confirming.get(), cancellation = canceling.get();
      if ("confirmed".equals(cancellation.path("outcome").asText())) {
        assertThat(outcome.path("outcome").asText()).isEqualTo("confirmed");
        assertThat(cancellation.path("commandId")).isEqualTo(raceConfirm.path("commandId"));
      } else {
        assertThat(cancellation.path("outcome").asText()).isEqualTo("canceled");
        assertThat(outcome.path("outcome").asText()).isEqualTo("rejected");
        assertThat(outcome.path("error").path("code").asText()).isEqualTo("TURN_CLOSED");
        assertSameSnapshot(game.subscribe(puzzle).snapshot(), current);
      }
      assertThat(game.confirm(a, raceConfirm)).isEqualTo(outcome);
      assertThat(game.cancel(a, raceCancel)).isEqualTo(cancellation);
    }

    JsonNode pinGroup = snapshot.path("groups").get(3);
    var pinTurn = game.begin(b, puzzle);
    game.preview(b, pinTurn.turnId(), 0, 1, action(UUID.randomUUID(), pinGroup, 0, 0));
    UUID command = UUID.randomUUID();
    JsonNode confirm = confirmPayload(puzzle, pinTurn.turnId(), 0, command);
    leases.pinConfirm(b, pinTurn.turnId(), 0, command, clock.instant());
    assertThat(game.confirm(b, confirm).path("outcome").asText()).isEqualTo("confirmed");
    assertThat(game.confirm(b, confirm).path("outcome").asText()).isEqualTo("confirmed");
    recoverCommittedConfirmationAfterDisconnect(puzzle, snapshot.path("groups").get(4));
  }

  private void recoverCommittedConfirmationAfterDisconnect(UUID puzzle, JsonNode group) {
    UUID subject = UUID.randomUUID(), command = UUID.randomUUID();
    jdbc.update("INSERT INTO game_subjects(subject_id) VALUES (?)", subject);
    var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
    ConfirmationJournal interrupted =
        new ConfirmationJournal(jdbc, validator, clock, writer) {
          @Override
          public JsonNode lookup(UUID s, UUID p, UUID c, JsonNode payload) {
            if (unavailable.get()) throw new IllegalStateException("temporary outage");
            return journal.lookup(s, p, c, payload);
          }

          @Override
          public JsonNode findForRecovery(UUID s, UUID c) {
            if (unavailable.get()) throw new IllegalStateException("temporary outage");
            return journal.findForRecovery(s, c);
          }

          @Override
          public JsonNode confirm(
              UUID s,
              UUID p,
              UUID c,
              JsonNode payload,
              Planner planner,
              java.util.function.Consumer<Instant> admitted) {
            journal.confirm(s, p, c, payload, planner, admitted);
            unavailable.set(true);
            throw new IllegalStateException("lost commit reply");
          }
        };
    TurnLeaseRegistry localLeases = new TurnLeaseRegistry(clock);
    GameCoordinator local =
        new GameCoordinator(
            boards, rules, localLeases, identities, interrupted, jdbc, clock, writer);
    java.util.List<JsonNode> received = new java.util.ArrayList<>();
    local.subscribe(
        UUID.randomUUID(),
        subject,
        puzzle,
        null,
        new GameCoordinator.Sink() {
          @Override
          public boolean offer(JsonNode message) {
            received.add(message.deepCopy());
            return true;
          }

          @Override
          public void slow() {
            throw new AssertionError("unexpected slow consumer");
          }
        });
    long before = local.subscribe(puzzle).snapshot().path("boardVersion").longValue();
    var turn = local.begin(subject, puzzle);
    local.preview(subject, turn.turnId(), 0, 1, action(UUID.randomUUID(), group, 0, 0));
    JsonNode frame = local.drainEvents().values().getLast().path("action");
    ObjectNode payload =
        mapper
            .createObjectNode()
            .put("puzzleId", puzzle.toString())
            .put("turnId", turn.turnId().toString())
            .put("commandId", command.toString())
            .put("turnRevision", 0)
            .put("generation", 0);
    payload.putArray("actions").add(frame);
    assertThatThrownBy(() -> local.confirm(subject, payload)).hasMessageContaining("unknown");
    local.disconnected(subject);
    local.tick();
    assertThat(localLeases.confirming(subject, turn.turnId(), 0)).isEqualTo(command);
    unavailable.set(false);
    local.tick();
    assertThat(localLeases.activeTurns()).doesNotContain(turn.turnId());
    assertThat(received).extracting(e -> e.path("type").asText()).contains("resyncRequired");
    assertThat(local.subscribe(puzzle).snapshot().path("boardVersion").longValue())
        .isEqualTo(before + 1);
    assertThat(journal.find(subject, command).path("outcome").asText()).isEqualTo("confirmed");
  }

  private void recoveryAndConnectedJoins(UUID puzzle, JsonNode snapshot) {
    UUID subject = UUID.randomUUID();
    jdbc.update("INSERT INTO game_subjects(subject_id) VALUES (?)", subject);
    JsonNode g0 = snapshot.path("groups").get(0),
        g1 = snapshot.path("groups").get(1),
        g2 = snapshot.path("groups").get(2),
        g3 = snapshot.path("groups").get(3);

    var canceledTurn = game.begin(subject, puzzle);
    game.preview(subject, canceledTurn.turnId(), 0, 1, action(UUID.randomUUID(), g0, 0, 0));
    UUID cancelId = UUID.randomUUID();
    JsonNode cancel = cancelPayload(puzzle, canceledTurn.turnId(), 0, 0, cancelId);
    JsonNode cancelResult = mapper.createObjectNode().put("outcome", "canceled");
    identities.record(subject, puzzle, cancelId, "cancel", cancel, cancelResult);
    assertThat(game.cancel(subject, cancel)).isEqualTo(cancelResult);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    assertThatThrownBy(() ->
            game.preview(subject, canceledTurn.turnId(), 0, 2, action(UUID.randomUUID(), g0, 0, 0)))
        .isInstanceOf(DraftRules.RuleError.class);

    var undoTurn = game.begin(subject, puzzle);
    UUID undoneAction = UUID.randomUUID();
    game.preview(subject, undoTurn.turnId(), 0, 1, action(undoneAction, g0, 0, 0));
    UUID undoId = UUID.randomUUID();
    JsonNode undo = undoPayload(puzzle, undoTurn.turnId(), 0, 0, 1, undoneAction, undoId);
    JsonNode undoResult = mapper.createObjectNode().put("generation", 1).put("turnRevision", 1);
    identities.record(subject, puzzle, undoId, "undo", undo, undoResult);
    assertThat(game.undo(subject, undo)).isEqualTo(undoResult);
    assertThat(game.undo(subject, undo)).isEqualTo(undoResult);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    assertThatThrownBy(() ->
            game.preview(subject, undoTurn.turnId(), 0, 2, action(UUID.randomUUID(), g0, 0, 0)))
        .isInstanceOf(DraftRules.RuleError.class);
    game.cancel(subject, cancelPayload(puzzle, undoTurn.turnId(), 1, 1, UUID.randomUUID()));

    var confirmedTurn = game.begin(subject, puzzle);
    game.preview(subject, confirmedTurn.turnId(), 0, 1, action(UUID.randomUUID(), g0, 0, 0));
    UUID confirmedCommand = UUID.randomUUID(), confirmedCancelId = UUID.randomUUID();
    leases.pinConfirm(subject, confirmedTurn.turnId(), 0, confirmedCommand, clock.instant());
    JsonNode confirmedCancel =
        cancelPayload(puzzle, confirmedTurn.turnId(), 0, 0, confirmedCancelId);
    JsonNode confirmedResult =
        mapper
            .createObjectNode()
            .put("outcome", "confirmed")
            .put("commandId", confirmedCommand.toString());
    identities.record(
        subject, puzzle, confirmedCancelId, "cancel", confirmedCancel, confirmedResult);
    assertThat(game.cancel(subject, confirmedCancel)).isEqualTo(confirmedResult);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();

    for (boolean reverse : new boolean[] {false, true}) {
      var turn = game.begin(subject, puzzle);
      UUID action = UUID.randomUUID();
      game.preview(subject, turn.turnId(), 0, 1, action(UUID.randomUUID(), g1, 0, 0));
      game.preview(subject, turn.turnId(), 0, 2, action(UUID.randomUUID(), g2, 0, 0));
      var bad =
          new PreviewGeneration.Action(
              action,
              "piece",
              piece(g0),
              List.of(piece(g0), piece(g1), piece(g2), piece(g3)),
              List.of(base(g0), base(g1), base(g2), base(g3)),
              new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
      assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 3, bad))
          .isInstanceOf(DraftRules.RuleError.class);
      assertThat(game.subscribe(puzzle).previews()).hasSize(2);
      var joined =
          new PreviewGeneration.Action(
              action,
              "piece",
              piece(g0),
              reverse
                  ? List.of(piece(g0), piece(g2), piece(g1))
                  : List.of(piece(g0), piece(g1), piece(g2)),
              reverse
                  ? List.of(base(g0), base(g2), base(g1))
                  : List.of(base(g0), base(g1), base(g2)),
              new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
      assertThat(game.preview(subject, turn.turnId(), 0, 3, joined).previews())
          .allSatisfy(p ->
              assertThat(p.action().affectedPieceIds())
                  .containsExactlyInAnyOrder(piece(g0), piece(g1), piece(g2)));
      game.cancel(subject, cancelPayload(puzzle, turn.turnId(), 0, 0, UUID.randomUUID()));
    }

    game.drainEvents();
  }

  private JsonNode cancelPayload(
      UUID puzzle, UUID turn, long generation, long revision, UUID request) {
    return mapper
        .createObjectNode()
        .put("schemaVersion", 1)
        .put("puzzleId", puzzle.toString())
        .put("turnId", turn.toString())
        .put("requestId", request.toString())
        .put("turnRevision", revision)
        .put("generation", generation);
  }

  private JsonNode undoPayload(
      UUID puzzle,
      UUID turn,
      long generation,
      long revision,
      long draftRevision,
      UUID action,
      UUID request) {
    ObjectNode payload = (ObjectNode) cancelPayload(puzzle, turn, generation, revision, request);
    payload.put("draftRevision", draftRevision);
    payload.put("actionId", action.toString());
    return payload;
  }

  private JsonNode confirmPayload(UUID puzzle, UUID turn, long generation, UUID command) {
    java.util.Map<String, JsonNode> actions = new java.util.TreeMap<>();
    for (JsonNode event : game.drainEvents().values()) {
      if ("preview".equals(event.path("type").asText())
          && turn.toString().equals(event.path("turnId").asText())) {
        JsonNode action = event.path("action");
        actions.put(action.path("actionId").textValue(), action.deepCopy());
      }
    }
    ObjectNode payload =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle.toString())
            .put("turnId", turn.toString())
            .put("commandId", command.toString())
            .put("turnRevision", 0)
            .put("generation", generation);
    var list = payload.putArray("actions");
    actions.values().forEach(list::add);
    assertThat(actions).isNotEmpty();
    return payload;
  }

  private void assertSameSnapshot(JsonNode actual, JsonNode expected) throws Exception {
    assertThat(actual.path("boardVersion").longValue())
        .isEqualTo(expected.path("boardVersion").longValue());
    java.util.Map<String, JsonNode> left = new java.util.TreeMap<>(),
        right = new java.util.TreeMap<>();
    for (JsonNode group : actual.path("groups"))
      left.put(group.path("groupId").textValue(), mapper.readTree(group.toString()));
    for (JsonNode group : expected.path("groups"))
      right.put(group.path("groupId").textValue(), mapper.readTree(group.toString()));
    assertThat(left).isEqualTo(right);
  }

  private UUID piece(JsonNode group) {
    return UUID.fromString(group.path("pieceIds").get(0).textValue());
  }

  private PreviewGeneration.BaseRevision base(JsonNode group) {
    return new PreviewGeneration.BaseRevision(
        UUID.fromString(group.path("groupId").textValue()), group.path("revision").longValue());
  }

  private PreviewGeneration.Action action(UUID id, JsonNode group, int x, int y) {
    return new PreviewGeneration.Action(
        id,
        "piece",
        piece(group),
        List.of(piece(group)),
        List.of(base(group)),
        new PreviewGeneration.Transform(new PreviewGeneration.Point(x, y), 0));
  }
}
