package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GamePreviewGeometryTest {
  @Test
  void rejectedUnjoinedMoveKeepsTheLastAcceptedPreviewAndCanBeRetried() throws Exception {
    var fixture = squareFixture(0, false);
    UUID firstSubject = UUID.randomUUID(), secondSubject = UUID.randomUUID();
    var first = fixture.game().begin(firstSubject, fixture.puzzle());
    var second = fixture.game().begin(secondSubject, fixture.puzzle());
    fixture
        .game()
        .preview(
            firstSubject,
            first.turnId(),
            0,
            1,
            squareAction(UUID.randomUUID(), fixture, 0, -200000, 0, false));
    UUID action = UUID.randomUUID();
    fixture
        .game()
        .preview(
            secondSubject,
            second.turnId(),
            0,
            1,
            squareAction(action, fixture, 1, -10000, 0, false));
    var accepted = fixture.game().subscribe(fixture.puzzle()).previews();

    assertThatThrownBy(() ->
            fixture
                .game()
                .preview(
                    secondSubject,
                    second.turnId(),
                    0,
                    2,
                    squareAction(action, fixture, 1, -100000, 0, false)))
        .isInstanceOf(DraftRules.RuleError.class)
        .hasMessageContaining("overlap");
    assertThat(fixture.game().subscribe(fixture.puzzle()).previews()).isEqualTo(accepted);
    assertThat(
            fixture
                .game()
                .preview(
                    secondSubject,
                    second.turnId(),
                    0,
                    2,
                    squareAction(action, fixture, 1, -5000, 0, false))
                .previews())
        .hasSize(1);
  }

  @Test
  void previewCannotOccupyAnotherPlayersSavedPosition() throws Exception {
    for (int rotation : new int[] {0, 90000, 180000, 270000}) {
      var fixture = squareFixture(rotation, false);
      UUID firstSubject = UUID.randomUUID(), secondSubject = UUID.randomUUID();
      var first = fixture.game().begin(firstSubject, fixture.puzzle());
      var second = fixture.game().begin(secondSubject, fixture.puzzle());
      fixture
          .game()
          .preview(
              firstSubject,
              first.turnId(),
              0,
              1,
              squareAction(UUID.randomUUID(), fixture, 0, -200000, rotation, false));
      UUID action = UUID.randomUUID();
      assertThatThrownBy(() ->
              fixture
                  .game()
                  .preview(
                      secondSubject,
                      second.turnId(),
                      0,
                      1,
                      squareAction(action, fixture, 1, -100000, rotation, false)))
          .isInstanceOf(DraftRules.RuleError.class)
          .hasMessageContaining("overlap");
      assertThat(fixture.game().subscribe(fixture.puzzle()).previews()).hasSize(1);
      assertThat(
              fixture
                  .game()
                  .preview(
                      secondSubject,
                      second.turnId(),
                      0,
                      1,
                      squareAction(action, fixture, 1, -10000, rotation, false))
                  .previews())
          .hasSize(1);
    }
  }

  @Test
  void snappedJoinCannotOccupyAnotherPlayersSavedPosition() throws Exception {
    for (int rotation : new int[] {0, 90000, 180000, 270000}) {
      var fixture = squareFixture(rotation, true);
      UUID firstSubject = UUID.randomUUID(), secondSubject = UUID.randomUUID();
      var first = fixture.game().begin(firstSubject, fixture.puzzle());
      var second = fixture.game().begin(secondSubject, fixture.puzzle());
      fixture
          .game()
          .preview(
              firstSubject,
              first.turnId(),
              0,
              1,
              squareAction(UUID.randomUUID(), fixture, 0, -200000, rotation, false));
      UUID action = UUID.randomUUID();
      assertThatThrownBy(() ->
              fixture
                  .game()
                  .preview(
                      secondSubject,
                      second.turnId(),
                      0,
                      1,
                      squareAction(action, fixture, 2, -5000, rotation, true)))
          .isInstanceOf(DraftRules.RuleError.class);
      assertThat(fixture.game().subscribe(fixture.puzzle()).previews()).hasSize(1);
      assertThat(
              fixture
                  .game()
                  .preview(
                      secondSubject,
                      second.turnId(),
                      0,
                      1,
                      squareAction(action, fixture, 2, -5000, rotation, false))
                  .previews())
          .hasSize(1);
    }
  }

  @Test
  void joinedUndoPreservesSurvivorAndRejectsAnOverlappingRestoration() throws Exception {
    for (boolean overlap : new boolean[] {false, true}) {
      var fixture = squareFixture(0, false);
      UUID subject = UUID.randomUUID();
      var turn = fixture.game().begin(subject, fixture.puzzle());
      UUID a = UUID.randomUUID(), b = UUID.randomUUID();
      int sequence = 0;
      if (overlap)
        fixture
            .game()
            .preview(
                subject,
                turn.turnId(),
                0,
                ++sequence,
                squareAction(UUID.randomUUID(), fixture, 2, 400000, 0, false));
      fixture
          .game()
          .preview(subject, turn.turnId(), 0, ++sequence, squareAction(a, fixture, 0, 0, 0, false));
      fixture
          .game()
          .preview(subject, turn.turnId(), 0, ++sequence, squareAction(b, fixture, 1, 0, 0, false));
      var joined =
          fixture
              .game()
              .preview(
                  subject, turn.turnId(), 0, ++sequence, squareAction(a, fixture, 0, 0, 0, true));
      UUID joinedId =
          joined.previews().stream()
              .map(PreviewGeneration.Preview::action)
              .filter(action -> action.actionId().equals(a))
              .findFirst()
              .orElseThrow()
              .targetId();
      int distance = overlap ? 100000 : -100000;
      var moved =
          new PreviewGeneration.Action(
              a,
              "piece",
              joinedId,
              List.of(fixture.pieces()[0], fixture.pieces()[1]),
              List.of(
                  new PreviewGeneration.BaseRevision(fixture.groups()[0], 0),
                  new PreviewGeneration.BaseRevision(fixture.groups()[1], 0)),
              new PreviewGeneration.Transform(new PreviewGeneration.Point(distance, 0), 0));
      fixture.game().preview(subject, turn.turnId(), 0, ++sequence, moved);
      ObjectNode undo =
          new ObjectMapper()
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("puzzleId", fixture.puzzle().toString())
              .put("turnId", turn.turnId().toString())
              .put("requestId", UUID.randomUUID().toString())
              .put("turnRevision", 0)
              .put("generation", 0)
              .put("draftRevision", sequence)
              .put("actionId", b.toString());
      if (overlap) {
        assertThatThrownBy(() -> fixture.game().undo(subject, undo))
            .isInstanceOf(DraftRules.RuleError.class)
            .hasMessageContaining("overlap");
        assertThat(fixture.game().subscribe(fixture.puzzle()).previews()).hasSize(3);
      } else {
        JsonNode result = fixture.game().undo(subject, undo);
        assertThat(
                result
                    .path("actions")
                    .get(0)
                    .path("intendedTransform")
                    .path("translation")
                    .path("x")
                    .intValue())
            .isEqualTo(distance);
        assertThat(fixture.game().subscribe(fixture.puzzle()).previews()).hasSize(1);
      }
    }
  }

  private record SquareFixture(GameCoordinator game, UUID puzzle, UUID[] pieces, UUID[] groups) {}

  private SquareFixture squareFixture(int rotation, boolean snap) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = board.path("definition").deepCopy();
    definition.set("pieces", squares.path("pieces").deepCopy());
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    ArrayNode groups = snapshot.putArray("groups");
    UUID[] pieceIds = new UUID[3], groupIds = new UUID[3];
    for (int i = 0; i < 3; i++) {
      pieceIds[i] = UUID.fromString(squares.path("pieces").get(i).path("pieceId").asText());
      groupIds[i] = UUID.randomUUID();
      ObjectNode group =
          groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
      group.putArray("pieceIds").add(pieceIds[i].toString());
      ObjectNode transform = group.putObject("transform");
      int translation = snap ? (i == 0 ? 277001 : i == 2 ? 500000 : 0) : 0;
      transform
          .putObject("translation")
          .put("x", axisX(rotation) * translation)
          .put("y", axisY(rotation) * translation);
      transform.put("rotation", rotation);
      group.put("finalPosition", false);
    }
    GameBoards boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class)))
        .thenReturn(
            Map.of(
                "definition",
                mapper.convertValue(definition, Map.class),
                "snapshot",
                mapper.convertValue(snapshot, Map.class),
                "allowance",
                Map.of("remaining", 5),
                "endsAt",
                "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(call -> UUID.randomUUID());
    when(identities.reserveGroup(any())).thenAnswer(call -> UUID.randomUUID());
    when(identities.record(any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> new GameIdentityLedger.Receipt(false, call.getArgument(5)));
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game =
        new GameCoordinator(
            boards,
            new DraftRules(mock(BoardValidator.class)),
            new TurnLeaseRegistry(clock),
            identities,
            mock(ConfirmationJournal.class),
            mock(JdbcTemplate.class),
            clock,
            mock(GameWriterGuard.class));
    return new SquareFixture(
        game, UUID.fromString(snapshot.path("puzzleId").asText()), pieceIds, groupIds);
  }

  private PreviewGeneration.Action squareAction(
      UUID id, SquareFixture fixture, int piece, int translation, int rotation, boolean join) {
    return new PreviewGeneration.Action(
        id,
        "piece",
        fixture.pieces()[piece],
        join
            ? List.of(fixture.pieces()[piece], fixture.pieces()[1])
            : List.of(fixture.pieces()[piece]),
        join
            ? List.of(
                new PreviewGeneration.BaseRevision(fixture.groups()[piece], 0),
                new PreviewGeneration.BaseRevision(fixture.groups()[1], 0))
            : List.of(new PreviewGeneration.BaseRevision(fixture.groups()[piece], 0)),
        new PreviewGeneration.Transform(
            new PreviewGeneration.Point(
                axisX(rotation) * translation, axisY(rotation) * translation),
            rotation));
  }

  private int axisX(int rotation) {
    return rotation == 0 ? 1 : rotation == 180000 ? -1 : 0;
  }

  private int axisY(int rotation) {
    return rotation == 90000 ? 1 : rotation == 270000 ? -1 : 0;
  }

  @Test
  void exhaustedJoinSearchIsRetryableAndLeavesTheDraftUnchanged() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = board.path("definition").deepCopy();
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    ArrayNode pieces = definition.putArray("pieces"), groups = snapshot.putArray("groups");
    UUID[] pieceIds = new UUID[12], groupIds = new UUID[12];
    for (int i = 0; i < 13; i++) {
      UUID pieceId = UUID.randomUUID(), groupId = UUID.randomUUID();
      if (i < 12) {
        pieceIds[i] = pieceId;
        groupIds[i] = groupId;
      }
      ObjectNode piece = squares.path("pieces").get(0).deepCopy();
      piece.put("pieceId", pieceId.toString());
      ((ObjectNode) piece.path("solvedPosition"))
          .put("x", i == 12 ? -82000 : (i % 4) * 100000)
          .put("y", i == 12 ? 0 : (i / 4) * 100000);
      for (JsonNode edge : piece.path("edges"))
        ((ObjectNode) edge).putNull("neighborPieceId").putNull("neighborEdgeId");
      pieces.add(piece);
      ObjectNode group = groups.addObject().put("groupId", groupId.toString()).put("revision", 0);
      group.putArray("pieceIds").add(pieceId.toString());
      ObjectNode transform = group.putObject("transform");
      transform.putObject("translation").put("x", i == 0 || i == 12 ? 0 : -5000).put("y", 0);
      transform.put("rotation", 0);
      group.put("finalPosition", false);
    }
    for (int i = 0; i < 12; i++) {
      if (i % 4 != 3) link(pieces, i, 1, i + 1, 3);
      if (i < 8) link(pieces, i, 2, i + 4, 0);
    }
    GameBoards boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class)))
        .thenReturn(
            Map.of(
                "definition",
                mapper.convertValue(definition, Map.class),
                "snapshot",
                mapper.convertValue(snapshot, Map.class),
                "allowance",
                Map.of("remaining", 5),
                "endsAt",
                "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(call -> UUID.randomUUID());
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game =
        new GameCoordinator(
            boards,
            new DraftRules(mock(BoardValidator.class)),
            new TurnLeaseRegistry(clock),
            identities,
            mock(ConfirmationJournal.class),
            mock(JdbcTemplate.class),
            clock,
            mock(GameWriterGuard.class));
    UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    var turn = game.begin(subject, puzzle);
    var action =
        new PreviewGeneration.Action(
            UUID.randomUUID(),
            "piece",
            pieceIds[0],
            List.of(pieceIds),
            java.util.Arrays.stream(groupIds)
                .map(id -> new PreviewGeneration.BaseRevision(id, 0))
                .toList(),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 1, action))
        .isInstanceOfSatisfying(
            DraftRules.RuleError.class,
            error -> {
              assertThat(error.code).isEqualTo("INTERNAL_ERROR");
              assertThat(error.getMessage()).contains("retry with fewer groups");
            });
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    var ordinary =
        new PreviewGeneration.Action(
            UUID.randomUUID(),
            "piece",
            pieceIds[0],
            List.of(pieceIds[0]),
            List.of(new PreviewGeneration.BaseRevision(groupIds[0], 0)),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    assertThat(game.preview(subject, turn.turnId(), 0, 1, ordinary).previews()).hasSize(1);
  }

  @Test
  void joinsGridWhenEarlyChoiceWouldStrandAnOffsetGroup() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    for (int rotation : new int[] {0, 90000, 180000, 270000}) {
      ObjectNode definition = board.path("definition").deepCopy();
      ObjectNode snapshot = board.path("snapshot").deepCopy();
      ArrayNode pieces = definition.putArray("pieces"), groups = snapshot.putArray("groups");
      UUID[] pieceIds = new UUID[16], groupIds = new UUID[16];
      int nextGroup = 3;
      for (int i = 0; i < 16; i++) {
        pieceIds[i] = UUID.randomUUID();
        int groupNumber = i == 0 ? 16 : i == 1 ? 1 : i == 4 ? 2 : nextGroup++;
        groupIds[i] = UUID.fromString(String.format("00000000-0000-4000-8000-%012d", groupNumber));
        ObjectNode piece = squares.path("pieces").get(0).deepCopy();
        piece.put("pieceId", pieceIds[i].toString());
        ((ObjectNode) piece.path("solvedPosition"))
            .put("x", (i % 4) * 100000)
            .put("y", (i / 4) * 100000);
        for (JsonNode edge : piece.path("edges"))
          ((ObjectNode) edge).putNull("neighborPieceId").putNull("neighborEdgeId");
        pieces.add(piece);
        ObjectNode group =
            groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
        group.putArray("pieceIds").add(pieceIds[i].toString());
        ObjectNode transform = group.putObject("transform");
        transform
            .putObject("translation")
            .put("x", i == 0 || i == 1 ? 0 : i == 4 ? 5000 : -5000)
            .put("y", 0);
        transform.put("rotation", rotation);
        group.put("finalPosition", false);
      }
      for (int i = 0; i < 16; i++) {
        if (i % 4 != 3) link(pieces, i, 1, i + 1, 3);
        if (i < 12) link(pieces, i, 2, i + 4, 0);
      }
      UUID unrelatedPiece = UUID.randomUUID();
      ObjectNode larger = squares.path("pieces").get(0).deepCopy();
      larger.put("pieceId", unrelatedPiece.toString());
      ((ObjectNode) larger.path("solvedPosition")).put("x", 800000).put("y", 800000);
      ((ObjectNode) larger.path("referencePoint")).put("x", 100000).put("y", 100000);
      for (JsonNode edge : larger.path("edges")) {
        ((ObjectNode) edge).putNull("neighborPieceId").putNull("neighborEdgeId");
        scalePoint((ObjectNode) edge.path("start"));
        for (JsonNode segment : edge.path("segments")) scalePoint((ObjectNode) segment.path("to"));
      }
      pieces.add(larger);
      ObjectNode unrelated =
          groups.addObject().put("groupId", UUID.randomUUID().toString()).put("revision", 0);
      unrelated.putArray("pieceIds").add(unrelatedPiece.toString());
      ObjectNode unrelatedTransform = unrelated.putObject("transform");
      unrelatedTransform.putObject("translation").put("x", 0).put("y", 0);
      unrelatedTransform.put("rotation", rotation);
      unrelated.put("finalPosition", false);
      GameBoards boards = mock(GameBoards.class);
      when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class)))
          .thenReturn(
              Map.of(
                  "definition",
                  mapper.convertValue(definition, Map.class),
                  "snapshot",
                  mapper.convertValue(snapshot, Map.class),
                  "allowance",
                  Map.of("remaining", 5),
                  "endsAt",
                  "2026-11-08T00:00:00Z"));
      GameIdentityLedger identities = mock(GameIdentityLedger.class);
      when(identities.reserveTurn(any(), any())).thenAnswer(call -> UUID.randomUUID());
      when(identities.reserveGroup(any())).thenAnswer(call -> UUID.randomUUID());
      Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
      GameCoordinator game =
          new GameCoordinator(
              boards,
              new DraftRules(mock(BoardValidator.class)),
              new TurnLeaseRegistry(clock),
              identities,
              mock(ConfirmationJournal.class),
              mock(JdbcTemplate.class),
              clock,
              mock(GameWriterGuard.class));
      UUID subject = UUID.randomUUID(),
          puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
      var turn = game.begin(subject, puzzle);
      var action =
          new PreviewGeneration.Action(
              UUID.randomUUID(),
              "piece",
              pieceIds[0],
              List.of(pieceIds),
              java.util.Arrays.stream(groupIds)
                  .map(id -> new PreviewGeneration.BaseRevision(id, 0))
                  .toList(),
              new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), rotation));
      var previews =
          org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
              java.time.Duration.ofSeconds(10),
              () -> game.preview(subject, turn.turnId(), 0, 1, action).previews());
      assertThat(previews).hasSize(1);
      assertThat(previews.getFirst().action().affectedPieceIds())
          .containsExactlyInAnyOrder(pieceIds);
      assertThat(previews.getFirst().action().intendedTransform().rotation()).isEqualTo(rotation);
    }
  }

  private void scalePoint(ObjectNode point) {
    point.put("x", point.path("x").longValue() * 2);
    point.put("y", point.path("y").longValue() * 2);
  }

  @Test
  void disconnectedSelectionIsRejectedWithoutEnumeratingJoinOrders() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = board.path("definition").deepCopy();
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    ArrayNode pieces = definition.putArray("pieces"), groups = snapshot.putArray("groups");
    UUID[] pieceIds = new UUID[16], groupIds = new UUID[16];
    for (int i = 0; i < 16; i++) {
      pieceIds[i] = UUID.randomUUID();
      groupIds[i] = UUID.randomUUID();
      ObjectNode piece = squares.path("pieces").get(0).deepCopy();
      piece.put("pieceId", pieceIds[i].toString());
      ((ObjectNode) piece.path("solvedPosition"))
          .put("x", i == 15 ? 3000000 : (i % 5) * 100000)
          .put("y", i == 15 ? 0 : (i / 5) * 100000);
      for (JsonNode edge : piece.path("edges"))
        ((ObjectNode) edge).putNull("neighborPieceId").putNull("neighborEdgeId");
      pieces.add(piece);
      ObjectNode group =
          groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
      group.putArray("pieceIds").add(pieceIds[i].toString());
      ObjectNode transform = group.putObject("transform");
      transform.putObject("translation").put("x", 0).put("y", 0);
      transform.put("rotation", 0);
      group.put("finalPosition", false);
    }
    for (int i = 0; i < 15; i++) {
      if (i % 5 != 4) link(pieces, i, 1, i + 1, 3);
      if (i < 10) link(pieces, i, 2, i + 5, 0);
    }
    GameBoards boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class)))
        .thenReturn(
            Map.of(
                "definition",
                mapper.convertValue(definition, Map.class),
                "snapshot",
                mapper.convertValue(snapshot, Map.class),
                "allowance",
                Map.of("remaining", 5),
                "endsAt",
                "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(call -> UUID.randomUUID());
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game =
        new GameCoordinator(
            boards,
            new DraftRules(mock(BoardValidator.class)),
            new TurnLeaseRegistry(clock),
            identities,
            mock(ConfirmationJournal.class),
            mock(JdbcTemplate.class),
            clock,
            mock(GameWriterGuard.class));
    UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    var turn = game.begin(subject, puzzle);
    var action =
        new PreviewGeneration.Action(
            UUID.randomUUID(),
            "piece",
            pieceIds[0],
            List.of(pieceIds),
            java.util.Arrays.stream(groupIds)
                .map(id -> new PreviewGeneration.BaseRevision(id, 0))
                .toList(),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(3),
        () ->
            assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 1, action))
                .isInstanceOf(DraftRules.RuleError.class)
                .hasMessageContaining("disconnected"));
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    var ordinary =
        new PreviewGeneration.Action(
            UUID.randomUUID(),
            "piece",
            pieceIds[0],
            List.of(pieceIds[0]),
            List.of(new PreviewGeneration.BaseRevision(groupIds[0], 0)),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    assertThat(game.preview(subject, turn.turnId(), 0, 1, ordinary).previews()).hasSize(1);
  }

  @Test
  void connectedJoinFindsAnOrderAcrossChangingSnapTransforms() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = board.path("definition").deepCopy();
    ArrayNode pieces = definition.putArray("pieces");
    UUID[] pieceIds = new UUID[4];
    UUID[] groupIds = {
      UUID.fromString("00000000-0000-4000-8000-000000000004"),
      UUID.fromString("00000000-0000-4000-8000-000000000002"),
      UUID.fromString("00000000-0000-4000-8000-000000000001"),
      UUID.fromString("00000000-0000-4000-8000-000000000003")
    };
    for (int i = 0; i < 4; i++) {
      pieceIds[i] = UUID.randomUUID();
      ObjectNode p = squares.path("pieces").get(0).deepCopy();
      p.put("pieceId", pieceIds[i].toString());
      ((ObjectNode) p.path("solvedPosition"))
          .put("x", i == 1 ? 100000 : 0)
          .put("y", i < 2 ? 0 : (i - 1) * 100000);
      for (JsonNode edge : p.path("edges")) {
        ((ObjectNode) edge).putNull("neighborPieceId").putNull("neighborEdgeId");
      }
      pieces.add(p);
    }
    link(pieces, 0, 1, 1, 3);
    link(pieces, 0, 2, 2, 0);
    link(pieces, 2, 2, 3, 0);
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    ArrayNode groups = snapshot.putArray("groups");
    for (int i = 0; i < 4; i++) {
      ObjectNode group =
          groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
      group.putArray("pieceIds").add(pieceIds[i].toString());
      ObjectNode transform = group.putObject("transform");
      transform.putObject("translation").put("x", i == 1 ? 5000 : i == 3 ? -5000 : 0).put("y", 0);
      transform.put("rotation", 0);
      group.put("finalPosition", false);
    }
    GameBoards boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class)))
        .thenReturn(
            Map.of(
                "definition",
                mapper.convertValue(definition, Map.class),
                "snapshot",
                mapper.convertValue(snapshot, Map.class),
                "allowance",
                Map.of("remaining", 5),
                "endsAt",
                "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(invocation -> UUID.randomUUID());
    when(identities.reserveGroup(any())).thenAnswer(invocation -> UUID.randomUUID());
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game =
        new GameCoordinator(
            boards,
            new DraftRules(mock(BoardValidator.class)),
            new TurnLeaseRegistry(clock),
            identities,
            mock(ConfirmationJournal.class),
            mock(JdbcTemplate.class),
            clock,
            mock(GameWriterGuard.class));
    UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    var turn = game.begin(subject, puzzle);
    var action =
        new PreviewGeneration.Action(
            UUID.randomUUID(),
            "piece",
            pieceIds[0],
            List.of(pieceIds),
            java.util.Arrays.stream(groupIds)
                .map(id -> new PreviewGeneration.BaseRevision(id, 0))
                .toList(),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    var previews = game.preview(subject, turn.turnId(), 0, 1, action).previews();
    assertThat(previews).hasSize(1);
    assertThat(previews.getFirst().action().affectedPieceIds()).containsExactlyInAnyOrder(pieceIds);
    assertThat(previews.getFirst().action().intendedTransform().translation().x()).isEqualTo(-5000);
  }

  private void link(ArrayNode pieces, int a, int edgeA, int b, int edgeB) {
    ObjectNode first = (ObjectNode) pieces.get(a).path("edges").get(edgeA);
    ObjectNode second = (ObjectNode) pieces.get(b).path("edges").get(edgeB);
    first
        .put("neighborPieceId", pieces.get(b).path("pieceId").asText())
        .put("neighborEdgeId", second.path("edgeId").asText());
    second
        .put("neighborPieceId", pieces.get(a).path("pieceId").asText())
        .put("neighborEdgeId", first.path("edgeId").asText());
  }

  @Test
  void invalidReleaseCannotBecomeValidBySnapping() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = board.path("definition").deepCopy();
    definition.set("pieces", squares.path("pieces").deepCopy());
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    ArrayNode groups = snapshot.putArray("groups");
    UUID[] groupIds = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    UUID[] pieces = new UUID[3];
    for (int i = 0; i < 3; i++) {
      pieces[i] = UUID.fromString(squares.path("pieces").get(i).path("pieceId").asText());
      ObjectNode group =
          groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
      group.putArray("pieceIds").add(pieces[i].toString());
      ObjectNode transform = group.putObject("transform");
      transform.putObject("translation").put("x", i == 2 ? -282001 : 0).put("y", 0);
      transform.put("rotation", 0);
      group.put("finalPosition", false);
    }
    GameBoards boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class)))
        .thenReturn(
            Map.of(
                "definition",
                mapper.convertValue(definition, Map.class),
                "snapshot",
                mapper.convertValue(snapshot, Map.class),
                "allowance",
                Map.of("remaining", 5),
                "endsAt",
                "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(invocation -> UUID.randomUUID());
    when(identities.reserveGroup(any())).thenAnswer(invocation -> UUID.randomUUID());
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game =
        new GameCoordinator(
            boards,
            new DraftRules(mock(BoardValidator.class)),
            new TurnLeaseRegistry(clock),
            identities,
            mock(ConfirmationJournal.class),
            mock(JdbcTemplate.class),
            clock,
            mock(GameWriterGuard.class));
    UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    var turn = game.begin(subject, puzzle);
    UUID action = UUID.randomUUID();
    var revisions =
        List.of(
            new PreviewGeneration.BaseRevision(groupIds[0], 0),
            new PreviewGeneration.BaseRevision(groupIds[1], 0));
    var invalid =
        new PreviewGeneration.Action(
            action,
            "piece",
            pieces[0],
            List.of(pieces[0], pieces[1]),
            revisions,
            new PreviewGeneration.Transform(new PreviewGeneration.Point(-5000, 0), 0));
    assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 1, invalid))
        .isInstanceOf(DraftRules.RuleError.class)
        .hasMessageContaining("overlap");
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    var valid =
        new PreviewGeneration.Action(
            action,
            "piece",
            pieces[0],
            List.of(pieces[0], pieces[1]),
            revisions,
            new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    assertThat(game.preview(subject, turn.turnId(), 0, 1, valid).previews()).hasSize(1);
  }
}
