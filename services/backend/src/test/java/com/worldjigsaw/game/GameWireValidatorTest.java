package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GameWireValidatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final GameWireValidator wire = new GameWireValidator();

  GameWireValidatorTest() throws Exception {}

  @Test
  void sharedCommandAndPreviewShapesValidateAndDuplicatesFailClosed() throws Exception {
    JsonNode fixture =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    ObjectNode confirm = fixture.path("confirm").deepCopy();
    assertThat(wire.parse("Confirm", confirm.toString())).isEqualTo(confirm);
    ObjectNode submit = fixture.path("preview").deepCopy();
    submit.put("type", "preview.submit");
    submit.remove("expiresAt");
    assertThat(wire.parse("PreviewSubmit", submit.toString())).isEqualTo(submit);
    ((ObjectNode) confirm.path("actions").get(1))
        .put("actionId", confirm.path("actions").get(0).path("actionId").textValue().toUpperCase());
    ObjectNode duplicateActions = confirm;
    assertThatThrownBy(() -> wire.parse("Confirm", duplicateActions.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    confirm = fixture.path("confirm").deepCopy();
    ArrayNode revisions = (ArrayNode) confirm.path("actions").get(0).path("baseRevisions");
    ((ObjectNode) revisions.get(0)).put("groupId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    revisions.addObject().put("groupId", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").put("revision", 3);
    ObjectNode invalid = confirm;
    assertThatThrownBy(() -> wire.parse("Confirm", invalid.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> wire.parse("BeginTurn", "{\"schemaVersion\":1,\"schemaVersion\":1}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> wire.parse("BeginTurn", " ".repeat(65537)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generatedPiecePreviewMatchesWireSchema() throws Exception {
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode group = board.path("snapshot").path("groups").get(0);
    ObjectNode p =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "preview.submit")
            .put("puzzleId", board.path("snapshot").path("puzzleId").textValue())
            .put("turnId", java.util.UUID.randomUUID().toString())
            .put("generation", 0)
            .put("sequence", 1);
    ObjectNode action =
        p.putObject("action")
            .put("actionId", java.util.UUID.randomUUID().toString())
            .put("kind", "piece")
            .put("targetId", group.path("pieceIds").get(0).textValue());
    action.putArray("affectedPieceIds").add(group.path("pieceIds").get(0).textValue());
    action
        .putArray("baseRevisions")
        .addObject()
        .put("groupId", group.path("groupId").textValue())
        .put("revision", group.path("revision").longValue());
    ObjectNode transform = action.putObject("intendedTransform");
    transform.putObject("translation").put("x", 0).put("y", 0);
    transform.put("rotation", 0);
    assertThat(wire.parse("PreviewSubmit", p.toString())).isNotNull();
  }
}
