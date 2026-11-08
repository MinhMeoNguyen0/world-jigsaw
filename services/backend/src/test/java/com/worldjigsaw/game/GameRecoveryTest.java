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
  private GameCoordinator game;
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
    GameBoards boards = mock(GameBoards.class);
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
    clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    leases = new TurnLeaseRegistry(clock);
    game = new GameCoordinator(boards, new DraftRules(mock(BoardValidator.class)), leases,
        identities, journal, jdbc, clock, mock(GameWriterGuard.class));
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
      assertThat(game.subscribe(puzzle).previews()).isEmpty();
      assertThat(leases.activeTurns()).doesNotContain(turn.turnId());
      var events = game.drainEvents();
      assertThat(events.values()).extracting(e -> e.path("type").asText()).contains("previewCleared");
      assertThat(events.resyncRequired()).isEqualTo("confirmed".equals(outcome));
    }
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
