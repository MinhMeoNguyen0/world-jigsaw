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
  void joinsGridWhenEarlyChoiceWouldStrandAnOffsetGroup() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board = mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares = mapper.readTree(Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
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
        ((ObjectNode) piece.path("solvedPosition")).put("x", (i % 4) * 100000).put("y", (i / 4) * 100000);
        for (JsonNode edge : piece.path("edges"))
          ((ObjectNode) edge).putNull("neighborPieceId").putNull("neighborEdgeId");
        pieces.add(piece);
        ObjectNode group = groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
        group.putArray("pieceIds").add(pieceIds[i].toString());
        ObjectNode transform = group.putObject("transform");
        transform.putObject("translation").put("x", i == 0 || i == 1 ? 0 : i == 4 ? 5000 : -5000).put("y", 0);
        transform.put("rotation", rotation);
        group.put("finalPosition", false);
      }
      for (int i = 0; i < 16; i++) {
        if (i % 4 != 3) link(pieces, i, 1, i + 1, 3);
        if (i < 12) link(pieces, i, 2, i + 4, 0);
      }
      GameBoards boards = mock(GameBoards.class);
      when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class))).thenReturn(Map.of(
          "definition", mapper.convertValue(definition, Map.class),
          "snapshot", mapper.convertValue(snapshot, Map.class),
          "allowance", Map.of("remaining", 5), "endsAt", "2026-11-08T00:00:00Z"));
      GameIdentityLedger identities = mock(GameIdentityLedger.class);
      when(identities.reserveTurn(any(), any())).thenAnswer(call -> UUID.randomUUID());
      when(identities.reserveGroup(any())).thenAnswer(call -> UUID.randomUUID());
      Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
      GameCoordinator game = new GameCoordinator(boards, new DraftRules(mock(BoardValidator.class)),
          new TurnLeaseRegistry(clock), identities, mock(ConfirmationJournal.class),
          mock(JdbcTemplate.class), clock, mock(GameWriterGuard.class));
      UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
      var turn = game.begin(subject, puzzle);
      var action = new PreviewGeneration.Action(UUID.randomUUID(), "piece", pieceIds[0],
          List.of(pieceIds), java.util.Arrays.stream(groupIds)
              .map(id -> new PreviewGeneration.BaseRevision(id, 0)).toList(),
          new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), rotation));
      var previews = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(10), () -> game.preview(subject, turn.turnId(), 0, 1, action).previews());
      assertThat(previews).hasSize(1);
      assertThat(previews.getFirst().action().affectedPieceIds()).containsExactlyInAnyOrder(pieceIds);
      assertThat(previews.getFirst().action().intendedTransform().rotation()).isEqualTo(rotation);
    }
  }

  @Test
  void disconnectedSelectionIsRejectedWithoutEnumeratingJoinOrders() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board = mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares = mapper.readTree(Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = board.path("definition").deepCopy();
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    ArrayNode pieces = definition.putArray("pieces"), groups = snapshot.putArray("groups");
    UUID[] pieceIds = new UUID[16], groupIds = new UUID[16];
    for (int i = 0; i < 16; i++) {
      pieceIds[i] = UUID.randomUUID();
      groupIds[i] = UUID.randomUUID();
      ObjectNode piece = squares.path("pieces").get(0).deepCopy();
      piece.put("pieceId", pieceIds[i].toString());
      ((ObjectNode) piece.path("solvedPosition")).put("x", i == 15 ? 3000000 : (i % 5) * 100000)
          .put("y", i == 15 ? 0 : (i / 5) * 100000);
      for (JsonNode edge : piece.path("edges"))
        ((ObjectNode) edge).putNull("neighborPieceId").putNull("neighborEdgeId");
      pieces.add(piece);
      ObjectNode group = groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
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
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class))).thenReturn(Map.of(
        "definition", mapper.convertValue(definition, Map.class),
        "snapshot", mapper.convertValue(snapshot, Map.class),
        "allowance", Map.of("remaining", 5), "endsAt", "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(call -> UUID.randomUUID());
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game = new GameCoordinator(boards, new DraftRules(mock(BoardValidator.class)),
        new TurnLeaseRegistry(clock), identities, mock(ConfirmationJournal.class),
        mock(JdbcTemplate.class), clock, mock(GameWriterGuard.class));
    UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    var turn = game.begin(subject, puzzle);
    var action = new PreviewGeneration.Action(UUID.randomUUID(), "piece", pieceIds[0],
        List.of(pieceIds), java.util.Arrays.stream(groupIds)
            .map(id -> new PreviewGeneration.BaseRevision(id, 0)).toList(),
        new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
        assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 1, action))
            .isInstanceOf(DraftRules.RuleError.class).hasMessageContaining("disconnected"));
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    var ordinary = new PreviewGeneration.Action(UUID.randomUUID(), "piece", pieceIds[0],
        List.of(pieceIds[0]), List.of(new PreviewGeneration.BaseRevision(groupIds[0], 0)),
        new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    assertThat(game.preview(subject, turn.turnId(), 0, 1, ordinary).previews()).hasSize(1);
  }

  @Test
  void connectedJoinFindsAnOrderAcrossChangingSnapTransforms() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board = mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares = mapper.readTree(Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
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
      ((ObjectNode) p.path("solvedPosition")).put("x", i == 1 ? 100000 : 0).put("y", i < 2 ? 0 : (i - 1) * 100000);
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
      ObjectNode group = groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
      group.putArray("pieceIds").add(pieceIds[i].toString());
      ObjectNode transform = group.putObject("transform");
      transform.putObject("translation").put("x", i == 1 ? 5000 : i == 3 ? -5000 : 0).put("y", 0);
      transform.put("rotation", 0);
      group.put("finalPosition", false);
    }
    GameBoards boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class))).thenReturn(Map.of(
        "definition", mapper.convertValue(definition, Map.class),
        "snapshot", mapper.convertValue(snapshot, Map.class),
        "allowance", Map.of("remaining", 5), "endsAt", "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(invocation -> UUID.randomUUID());
    when(identities.reserveGroup(any())).thenAnswer(invocation -> UUID.randomUUID());
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game = new GameCoordinator(boards, new DraftRules(mock(BoardValidator.class)),
        new TurnLeaseRegistry(clock), identities, mock(ConfirmationJournal.class),
        mock(JdbcTemplate.class), clock, mock(GameWriterGuard.class));
    UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    var turn = game.begin(subject, puzzle);
    var action = new PreviewGeneration.Action(UUID.randomUUID(), "piece", pieceIds[0],
        List.of(pieceIds), java.util.Arrays.stream(groupIds)
            .map(id -> new PreviewGeneration.BaseRevision(id, 0)).toList(),
        new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    var previews = game.preview(subject, turn.turnId(), 0, 1, action).previews();
    assertThat(previews).hasSize(1);
    assertThat(previews.getFirst().action().affectedPieceIds()).containsExactlyInAnyOrder(pieceIds);
    assertThat(previews.getFirst().action().intendedTransform().translation().x()).isEqualTo(-5000);
  }

  private void link(ArrayNode pieces, int a, int edgeA, int b, int edgeB) {
    ObjectNode first = (ObjectNode) pieces.get(a).path("edges").get(edgeA);
    ObjectNode second = (ObjectNode) pieces.get(b).path("edges").get(edgeB);
    first.put("neighborPieceId", pieces.get(b).path("pieceId").asText())
        .put("neighborEdgeId", second.path("edgeId").asText());
    second.put("neighborPieceId", pieces.get(a).path("pieceId").asText())
        .put("neighborEdgeId", first.path("edgeId").asText());
  }

  @Test
  void invalidReleaseCannotBecomeValidBySnapping() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode board = mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode squares = mapper.readTree(Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = board.path("definition").deepCopy();
    definition.set("pieces", squares.path("pieces").deepCopy());
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    ArrayNode groups = snapshot.putArray("groups");
    UUID[] groupIds = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    UUID[] pieces = new UUID[3];
    for (int i = 0; i < 3; i++) {
      pieces[i] = UUID.fromString(squares.path("pieces").get(i).path("pieceId").asText());
      ObjectNode group = groups.addObject().put("groupId", groupIds[i].toString()).put("revision", 0);
      group.putArray("pieceIds").add(pieces[i].toString());
      ObjectNode transform = group.putObject("transform");
      transform.putObject("translation").put("x", i == 2 ? -290001 : 0).put("y", 0);
      transform.put("rotation", 0);
      group.put("finalPosition", false);
    }
    GameBoards boards = mock(GameBoards.class);
    when(boards.currentFor(org.mockito.ArgumentMatchers.nullable(UUID.class))).thenReturn(Map.of(
        "definition", mapper.convertValue(definition, Map.class),
        "snapshot", mapper.convertValue(snapshot, Map.class),
        "allowance", Map.of("remaining", 5), "endsAt", "2026-11-08T00:00:00Z"));
    GameIdentityLedger identities = mock(GameIdentityLedger.class);
    when(identities.reserveTurn(any(), any())).thenAnswer(invocation -> UUID.randomUUID());
    when(identities.reserveGroup(any())).thenAnswer(invocation -> UUID.randomUUID());
    Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    GameCoordinator game = new GameCoordinator(boards, new DraftRules(mock(BoardValidator.class)),
        new TurnLeaseRegistry(clock), identities, mock(ConfirmationJournal.class),
        mock(JdbcTemplate.class), clock, mock(GameWriterGuard.class));
    UUID subject = UUID.randomUUID(), puzzle = UUID.fromString(snapshot.path("puzzleId").asText());
    var turn = game.begin(subject, puzzle);
    UUID action = UUID.randomUUID();
    var revisions = List.of(new PreviewGeneration.BaseRevision(groupIds[0], 0),
        new PreviewGeneration.BaseRevision(groupIds[1], 0));
    var invalid = new PreviewGeneration.Action(action, "piece", pieces[0],
        List.of(pieces[0], pieces[1]), revisions,
        new PreviewGeneration.Transform(new PreviewGeneration.Point(-5000, 0), 0));
    assertThatThrownBy(() -> game.preview(subject, turn.turnId(), 0, 1, invalid))
        .isInstanceOf(DraftRules.RuleError.class).hasMessageContaining("overlap");
    assertThat(game.subscribe(puzzle).previews()).isEmpty();
    var valid = new PreviewGeneration.Action(action, "piece", pieces[0],
        List.of(pieces[0], pieces[1]), revisions,
        new PreviewGeneration.Transform(new PreviewGeneration.Point(0, 0), 0));
    assertThat(game.preview(subject, turn.turnId(), 0, 1, valid).previews()).hasSize(1);
  }
}
