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
