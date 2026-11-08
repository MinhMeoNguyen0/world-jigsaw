package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GameRecoveryTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final UUID subject = UUID.randomUUID();
  private final Map<UUID, GameIdentityLedger.Receipt> receipts = new HashMap<>();
  private final AtomicBoolean loseReply = new AtomicBoolean();
  private final AtomicBoolean unavailable = new AtomicBoolean();
  private final AtomicBoolean commit = new AtomicBoolean(true);
  private final java.util.concurrent.atomic.AtomicReference<Instant> now =
      new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-07T12:00:00Z"));
  private GameCoordinator game;
  private GameBoards boards;
  private GameIdentityLedger identities;
  private ConfirmationJournal journal;
  private TurnLeaseRegistry leases;
  private Clock clock;
  private JsonNode snapshot;
  private UUID puzzle;

  @BeforeEach
  void setup() throws Exception {
    JsonNode board = mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    snapshot = board.path("snapshot");
    puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class))).thenReturn(Map.of(
        "definition", mapper.convertValue(board.path("definition"), Map.class),
        "snapshot", mapper.convertValue(snapshot, Map.class),
        "allowance", Map.of("remaining", 5), "endsAt", "2026-11-08T00:00:00Z"));
    identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(call -> UUID.randomUUID());
    when(identities.ownsTurn(any(), any(), any())).thenReturn(true);
    when(identities.lookup(any(), any(), anyString(), any()))
        .thenAnswer(call -> {
          if (unavailable.get()) throw new IllegalStateException("database unavailable");
          return receipts.get(call.getArgument(1));
        });
    doAnswer(call -> {
      GameIdentityLedger.Receipt receipt = new GameIdentityLedger.Receipt(false, call.getArgument(5));
      if (commit.get()) receipts.put(call.getArgument(2), receipt);
      if (loseReply.getAndSet(false)) throw new IllegalStateException("lost reply");
      return receipt;
    }).when(identities).record(any(), any(), any(), anyString(), any(), any());
    journal = mock(ConfirmationJournal.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class)))
        .thenReturn(List.of(Map.of("board_version", 0L, "used", 0)));
    clock = new Clock() {
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(instant(), zone); }
      @Override public Instant instant() { return now.get(); }
    };
    leases = new TurnLeaseRegistry(clock);
    GameWriterGuard writer = mock(GameWriterGuard.class);
    when(writer.healthy()).thenReturn(true);
    game = new GameCoordinator(boards, new DraftRules(mock(BoardValidator.class)), leases,
        identities, journal, jdbc, clock, writer);
  }

  @Test
  void lostCancelReplyIsReconciledBeforeAnotherPreview() {
    var turn = game.begin(subject, puzzle);
    var action = action(UUID.randomUUID(), snapshot.path("groups").get(0));
    game.preview(subject, turn.turnId(), 0, 1, action);
    JsonNode cancel = payload(turn.turnId(), UUID.randomUUID());
    loseReply.set(true);
    assertThatThrownBy(() -> game.cancel(subject, cancel)).hasMessageContaining("lost reply");
    assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 2, action))
        .isInstanceOf(DraftRules.RuleError.class);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    assertThat(game.cancel(subject, cancel).path("outcome").asText()).isEqualTo("canceled");
  }

  @Test
  void lostUndoReplyFencesOldGenerationBeforeAnotherPreview() {
    var turn = game.begin(subject, puzzle);
    UUID actionId = UUID.randomUUID();
    var action = action(actionId, snapshot.path("groups").get(0));
    game.preview(subject, turn.turnId(), 0, 1, action);
    ObjectNode undo = payload(turn.turnId(), UUID.randomUUID());
    undo.put("actionId", actionId.toString());
    loseReply.set(true);
    assertThatThrownBy(() -> game.undo(subject, undo)).hasMessageContaining("lost reply");
    assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 2, action))
        .isInstanceOf(DraftRules.RuleError.class);
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    assertThat(game.undo(subject, undo).path("generation").asLong()).isEqualTo(1);
    game.preview(subject, turn.turnId(), 1, 1, action(UUID.randomUUID(), snapshot.path("groups").get(0)));
  }

  @Test
  void unresolvedReceiptBlocksOperationsUntilDatabaseCanProveNoCommit() {
    var turn = game.begin(subject, puzzle);
    var action = action(UUID.randomUUID(), snapshot.path("groups").get(0));
    game.preview(subject, turn.turnId(), 0, 1, action);
    JsonNode cancel = payload(turn.turnId(), UUID.randomUUID());
    commit.set(false);
    loseReply.set(true);
    assertThatThrownBy(() -> game.cancel(subject, cancel)).hasMessageContaining("lost reply");
    unavailable.set(true);
    assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 2, action))
        .hasMessageContaining("database unavailable");
    unavailable.set(false);
    assertThat(game.preview(subject, turn.turnId(), 0, 2, action).previews()).hasSize(1);
    commit.set(true);
    assertThat(game.cancel(subject, cancel).path("outcome").asText()).isEqualTo("canceled");
  }

  @Test
  void statusResolvesConfirmedRejectedAndAbsentPins() {
    for (String outcome : List.of("confirmed", "rejected", "unknown")) {
      var turn = game.begin(subject, puzzle);
      var action = action(UUID.randomUUID(), snapshot.path("groups").get(0));
      game.preview(subject, turn.turnId(), 0, 1, action);
      game.drainEvents();
      UUID command = UUID.randomUUID();
      leases.pinConfirm(subject, turn.turnId(), 0, command, clock.instant());
      JsonNode result = "unknown".equals(outcome) ? null : mapper.createObjectNode().put("outcome", outcome);
      when(journal.find(subject, command)).thenReturn(result);
      assertThat(game.commandStatus(subject, command)).isEqualTo(result);
      var events = game.drainEvents();
      if (result == null) {
        assertThat(game.subscribe(puzzle).previews()).hasSize(1);
        assertThat(leases.activeTurns()).contains(turn.turnId());
        assertThat(events.values()).extracting(e -> e.path("type").asText()).contains("preview");
        assertThat(game.preview(subject, turn.turnId(), 0, 2, action).previews()).hasSize(1);
      } else {
        assertThat(game.subscribe(puzzle).previews()).isEmpty();
        assertThat(leases.activeTurns()).doesNotContain(turn.turnId());
        assertThat(events.values()).extracting(e -> e.path("type").asText()).contains("previewCleared");
      }
      assertThat(events.resyncRequired()).isEqualTo("confirmed".equals(outcome));
    }
  }

  @Test
  void rolledBackConfirmationCanBeRetriedAfterImmediateLookup() {
    var turn = game.begin(subject, puzzle);
    var action = action(UUID.randomUUID(), snapshot.path("groups").get(0));
    game.preview(subject, turn.turnId(), 0, 1, action);
    JsonNode frame = game.drainEvents().values().getLast().path("action");
    UUID command = UUID.randomUUID();
    ObjectNode confirm = mapper.createObjectNode().put("puzzleId", puzzle.toString())
        .put("turnId", turn.turnId().toString()).put("commandId", command.toString())
        .put("turnRevision", 0).put("generation", 0);
    confirm.putArray("actions").add(frame);
    AtomicBoolean first = new AtomicBoolean(true);
    doAnswer(call -> {
      java.util.function.Consumer<Instant> admitted = call.getArgument(5);
      admitted.accept(clock.instant());
      if (first.getAndSet(false)) throw new IllegalStateException("rolled back");
      return mapper.createObjectNode().put("outcome", "confirmed");
    }).when(journal).confirm(any(), any(), any(), any(), any(), any());
    assertThatThrownBy(() -> game.confirm(subject, confirm)).hasMessageContaining("rolled back");
    assertThat(game.subscribe(puzzle).previews()).hasSize(1);
    assertThat(game.confirm(subject, confirm).path("outcome").asText()).isEqualTo("confirmed");
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
  }

  @Test
  void statusUnknownAllowsSameConfirmationToBeRetried() {
    var turn = game.begin(subject, puzzle);
    var action = action(UUID.randomUUID(), snapshot.path("groups").get(0));
    game.preview(subject, turn.turnId(), 0, 1, action);
    JsonNode frame = game.drainEvents().values().getLast().path("action");
    UUID command = UUID.randomUUID();
    ObjectNode confirm = mapper.createObjectNode().put("puzzleId", puzzle.toString())
        .put("turnId", turn.turnId().toString()).put("commandId", command.toString())
        .put("turnRevision", 0).put("generation", 0);
    confirm.putArray("actions").add(frame);
    leases.pinConfirm(subject, turn.turnId(), 0, command, clock.instant());
    assertThat(game.commandStatus(subject, command)).isNull();
    doAnswer(call -> {
      java.util.function.Consumer<Instant> admitted = call.getArgument(5);
      admitted.accept(clock.instant());
      return mapper.createObjectNode().put("outcome", "confirmed");
    }).when(journal).confirm(any(), any(), any(), any(), any(), any());
    assertThat(game.confirm(subject, confirm).path("outcome").asText()).isEqualTo("confirmed");
  }

  @Test
  void retryAfterUnknownOutcomeCannotCommitAnExpiredTurn() {
    var turn = game.begin(subject, puzzle);
    game.preview(subject, turn.turnId(), 0, 1, action(UUID.randomUUID(), snapshot.path("groups").get(0)));
    JsonNode frame = game.drainEvents().values().getLast().path("action");
    UUID command = UUID.randomUUID();
    ObjectNode confirm = mapper.createObjectNode().put("puzzleId", puzzle.toString())
        .put("turnId", turn.turnId().toString()).put("commandId", command.toString())
        .put("turnRevision", 0).put("generation", 0);
    confirm.putArray("actions").add(frame);
    when(journal.lookup(subject, puzzle, command, confirm))
        .thenReturn(null).thenThrow(new IllegalStateException("unavailable")).thenReturn(null);
    doAnswer(call -> {
      java.util.function.Consumer<Instant> admitted = call.getArgument(5);
      admitted.accept(clock.instant());
      throw new IllegalStateException("rolled back");
    }).when(journal).confirm(any(), any(), any(), any(), any(), any());
    doAnswer(call -> {
      ConfirmationJournal.Planner planner = call.getArgument(4);
      try {
        planner.apply(null, null, 0);
        throw new AssertionError("expired turn must be rejected");
      } catch (ConfirmationJournal.Rejected rejected) {
        ObjectNode result = mapper.createObjectNode().put("outcome", "rejected");
        result.putObject("error").put("code", rejected.code);
        return result;
      }
    }).when(journal).confirm(any(), any(), any(), any(), any());
    assertThatThrownBy(() -> game.confirm(subject, confirm)).hasMessageContaining("unknown");
    now.set(now.get().plusSeconds(31));
    assertThat(game.confirm(subject, confirm).path("error").path("code").asText()).isEqualTo("TURN_CLOSED");
    org.mockito.Mockito.verify(journal, org.mockito.Mockito.times(1))
        .confirm(any(), any(), any(), any(), any(), any());
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
  }

  @Test
  void tickRecoversAbandonedConfirmedAndUncommittedTurnsAfterOutage() {
    for (String outcome : List.of("confirmed", "rejected", "absent")) {
      var turn = game.begin(subject, puzzle);
      game.preview(subject, turn.turnId(), 0, 1, action(UUID.randomUUID(), snapshot.path("groups").get(0)));
      JsonNode frame = game.drainEvents().values().getLast().path("action");
      UUID command = UUID.randomUUID();
      ObjectNode confirm = mapper.createObjectNode().put("puzzleId", puzzle.toString())
          .put("turnId", turn.turnId().toString()).put("commandId", command.toString())
          .put("turnRevision", 0).put("generation", 0);
      confirm.putArray("actions").add(frame);
      when(journal.lookup(subject, puzzle, command, confirm))
          .thenReturn(null).thenThrow(new IllegalStateException("unavailable"));
      doAnswer(call -> {
        java.util.function.Consumer<Instant> admitted = call.getArgument(5);
        admitted.accept(clock.instant());
        throw new IllegalStateException("reply lost");
      }).when(journal).confirm(any(), any(), any(), any(), any(), any());
      assertThatThrownBy(() -> game.confirm(subject, confirm)).hasMessageContaining("unknown");
      game.disconnected(subject);
      JsonNode result = "absent".equals(outcome) ? null : mapper.createObjectNode().put("outcome", outcome);
      when(journal.findForRecovery(subject, command))
          .thenThrow(new IllegalStateException("unavailable")).thenReturn(result);
      game.tick();
      assertThat(leases.confirming(subject, turn.turnId(), 0)).isEqualTo(command);
      game.drainEvents();
      game.tick();
      var events = game.drainEvents();
      if (result == null) {
        assertThat(leases.confirming(subject, turn.turnId(), 0)).isNull();
        assertThat(game.subscribe(puzzle).previews()).hasSize(1);
        game.cancel(subject, payload(turn.turnId(), UUID.randomUUID()));
      } else {
        assertThat(leases.activeTurns()).doesNotContain(turn.turnId());
        assertThat(game.subscribe(puzzle).previews()).isEmpty();
        assertThat(events.values()).extracting(e -> e.path("type").asText()).contains("previewCleared");
        assertThat(events.resyncRequired()).isEqualTo("confirmed".equals(outcome));
      }
    }
  }

  @Test
  void tickRetiresAbandonedUncommittedTurnAfterItsLeaseExpires() {
    var turn = game.begin(subject, puzzle);
    game.preview(subject, turn.turnId(), 0, 1, action(UUID.randomUUID(), snapshot.path("groups").get(0)));
    UUID command = UUID.randomUUID();
    leases.pinConfirm(subject, turn.turnId(), 0, command, clock.instant());
    now.set(now.get().plusSeconds(31));
    game.tick();
    assertThat(leases.activeTurns()).doesNotContain(turn.turnId());
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    assertThat(game.drainEvents().values()).filteredOn(e -> "previewCleared".equals(e.path("type").asText()))
        .extracting(e -> e.path("reason").asText()).contains("expired");
  }

  @Test
  void rolloverRetriesAfterTransientBoardQueryFailure() {
    java.util.List<JsonNode> messages = new java.util.ArrayList<>();
    game.subscribe(UUID.randomUUID(), subject, puzzle, null, new GameCoordinator.Sink() {
      @Override public boolean offer(JsonNode message) { messages.add(message.deepCopy()); return true; }
      @Override public void slow() { throw new AssertionError("unexpected slow consumer"); }
    });
    now.set(Instant.parse("2026-11-08T00:00:00Z"));
    when(boards.current()).thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(Map.of("snapshot", Map.of("puzzleId", puzzle.toString())));
    assertThatThrownBy(() -> game.tick()).hasMessageContaining("database unavailable");
    assertThat(messages).extracting(m -> m.path("type").asText()).containsExactly("subscribed");
    game.tick();
    assertThat(messages).extracting(m -> m.path("type").asText())
        .containsExactly("subscribed", "puzzleChanged");
    assertThat(messages.getLast().path("available").asBoolean()).isTrue();
    assertThat(messages.getLast().path("puzzleId").asText()).isEqualTo(puzzle.toString());
    game.tick();
    assertThat(messages).hasSize(2);
  }

  private ObjectNode payload(UUID turn, UUID request) {
    return mapper.createObjectNode().put("schemaVersion", 1).put("puzzleId", puzzle.toString())
        .put("turnId", turn.toString()).put("requestId", request.toString())
        .put("turnRevision", 0).put("generation", 0);
  }

  private PreviewGeneration.Action action(UUID id, JsonNode group) {
    UUID piece = UUID.fromString(group.path("pieceIds").get(0).asText());
    UUID groupId = UUID.fromString(group.path("groupId").asText());
    return new PreviewGeneration.Action(id, "piece", piece, List.of(piece),
        List.of(new PreviewGeneration.BaseRevision(groupId, 0)),
        new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
  }
}
