package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameWireValidatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final GameWireValidator wire = new GameWireValidator();

  GameWireValidatorTest() throws Exception {}

  @Test
  void undoReceiptAcceptsUpToEightySurvivors() throws Exception {
    ObjectNode schema =
        (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/contracts/v1/schema.json"));
    schema.remove("oneOf");
    schema.put("$ref", "#/$defs/UndoResult");
    var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema);
    JsonNode fixture =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    ObjectNode result =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", fixture.path("confirm").path("puzzleId").textValue())
            .put("turnId", fixture.path("confirm").path("turnId").textValue())
            .put("requestId", UUID.randomUUID().toString())
            .put("turnRevision", 1)
            .put("generation", 1)
            .put("actionId", UUID.randomUUID().toString());
    ArrayNode actions = result.putArray("actions");
    for (int i = 0; i < 81; i++) {
      ObjectNode action = fixture.path("confirm").path("actions").get(0).deepCopy();
      action.put("actionId", UUID.randomUUID().toString());
      actions.add(action);
      if (i == 5 || i == 79) assertThat(validator.validate(result)).isEmpty();
    }
    assertThat(validator.validate(result)).isNotEmpty();
  }

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
