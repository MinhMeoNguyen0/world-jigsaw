package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalGeometryTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void independentSquareVectorsCoverOverlapAndSnapBoundaries() throws Exception {
    JsonNode vectors =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = mapper.createObjectNode();
    definition.set("pieces", vectors.path("pieces"));
    for (JsonNode vector : vectors.path("collisionCases")) {
      ObjectNode snapshot = mapper.createObjectNode();
      ArrayNode groups = snapshot.putArray("groups");
      for (int i = 0; i < vectors.path("pieces").size(); i++) {
        ObjectNode group = groups.addObject();
        group.putArray("pieceIds").add(vectors.path("pieces").get(i).path("pieceId").textValue());
        group.set("transform", vector.path("transforms").get(i));
      }
      assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot))
          .as(vector.path("name").textValue())
          .isEqualTo(vector.path("forbidden").booleanValue());
    }
    for (JsonNode vector : vectors.path("snapCases")) {
      assertThat(
              LocalGeometry.canSnap(
                  vectors.path("pieces").get(vector.path("moving").intValue()),
                  vector.path("movingTransform"),
                  vectors.path("pieces").get(vector.path("target").intValue()),
                  vector.path("targetTransform")))
          .as(vector.path("name").textValue())
          .isEqualTo(vector.path("allowed").booleanValue());
    }
  }

  @Test
  void sampledRealCurvesMatchWhenSolvedAndRejectLegacyOrientations() throws Exception {
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/review-60.json").toFile());
    ObjectNode snapshot = board.path("snapshot").deepCopy();
    for (JsonNode group : snapshot.path("groups")) {
      ObjectNode transform = (ObjectNode) group.path("transform");
      transform.put("rotation", 0);
      ((ObjectNode) transform.path("translation")).put("x", 0).put("y", 0);
    }
    assertThat(LocalGeometry.hasForbiddenOverlap(board.path("definition"), snapshot)).isFalse();
    ((ObjectNode) snapshot.path("groups").get(0).path("transform")).put("rotation", 12345);
    assertThatThrownBy(() -> LocalGeometry.hasForbiddenOverlap(board.path("definition"), snapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported transform");
  }

  @Test
  void malformedValuesAndOverlappingGroupMembersFailClosed() throws Exception {
    JsonNode vectors =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    ObjectNode definition = mapper.createObjectNode();
    definition.set("pieces", vectors.path("pieces").deepCopy());
    ObjectNode snapshot = mapper.createObjectNode();
    ArrayNode groups = snapshot.putArray("groups");
    ObjectNode group = groups.addObject();
    ArrayNode ids = group.putArray("pieceIds");
    for (JsonNode piece : definition.path("pieces")) ids.add(piece.path("pieceId").textValue());
    group.set(
        "transform", vectors.path("collisionCases").get(0).path("transforms").get(0).deepCopy());
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isFalse();
    ((ObjectNode) definition.path("pieces").get(1).path("solvedPosition")).put("x", 0);
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isTrue();
    ((ObjectNode) group.path("transform").path("translation")).put("x", Long.MIN_VALUE);
    assertThatThrownBy(() -> LocalGeometry.hasForbiddenOverlap(definition, snapshot))
        .isInstanceOf(IllegalArgumentException.class);
    ((ObjectNode) group.path("transform").path("translation"))
        .put("x", new BigInteger("18446744073709551616"));
    assertThatThrownBy(() -> LocalGeometry.hasForbiddenOverlap(definition, snapshot))
        .isInstanceOf(IllegalArgumentException.class);
    ((ObjectNode) group.path("transform").path("translation")).put("x", 0);
    ((ObjectNode) group.path("transform")).put("rotation", 12345);
    assertThatThrownBy(() -> LocalGeometry.hasForbiddenOverlap(definition, snapshot))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void realCurvedNeighborsPermitTouchingAndSmallMovementButRejectDeepIntrusion() throws Exception {
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/review-60.json").toFile());
    ObjectNode definition = mapper.createObjectNode();
    ArrayNode pieces = definition.putArray("pieces");
    pieces.add(board.path("definition").path("pieces").get(0));
    pieces.add(board.path("definition").path("pieces").get(1));
    ObjectNode snapshot = mapper.createObjectNode();
    ArrayNode groups = snapshot.putArray("groups");
    for (int i = 0; i < 2; i++) {
      ObjectNode group = groups.addObject();
      group.putArray("pieceIds").add(pieces.get(i).path("pieceId").textValue());
      ObjectNode transform = group.putObject("transform");
      transform.put("rotation", 0);
      transform.putObject("translation").put("x", 0).put("y", 0);
    }
    assertThat(
            LocalGeometry.canSnap(
                pieces.get(0),
                groups.get(0).path("transform"),
                pieces.get(1),
                groups.get(1).path("transform")))
        .isTrue();
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isFalse();
    ObjectNode move = (ObjectNode) groups.get(1).path("transform").path("translation");
    move.put("x", -1000);
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isFalse();
    move.put("x", -60000);
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isTrue();
  }

  @Test
  void reviewedRotatedContoursPermitShallowOverlapButRejectStacking() throws Exception {
    JsonNode board =
        mapper.readTree(
            Path.of("../../packages/generator/fixtures/hoi-an-surround-60.json").toFile());
    String source = "b5aab6b4-44e8-5359-babb-5429aa97d18f";
    String target = "0d5f9eed-91de-529e-b324-840c45029401";
    ObjectNode definition = mapper.createObjectNode();
    ArrayNode pieces = definition.putArray("pieces");
    for (JsonNode piece : board.path("definition").path("pieces"))
      if (piece.path("pieceId").asText().equals(source)
          || piece.path("pieceId").asText().equals(target)) pieces.add(piece);
    assertThat(pieces.size()).isEqualTo(2);
    ObjectNode snapshot = mapper.createObjectNode();
    ArrayNode groups = snapshot.putArray("groups");
    ObjectNode moving = null;
    for (JsonNode raw : board.path("snapshot").path("groups")) {
      String id = raw.path("pieceIds").get(0).asText();
      if (id.equals(source) || id.equals(target)) {
        ObjectNode group = raw.deepCopy();
        groups.add(group);
        if (id.equals(source)) moving = group;
      }
    }
    assertThat(moving).isNotNull();
    ObjectNode transform = (ObjectNode) moving.path("transform");
    transform.put("rotation", 90000);
    ObjectNode translation = (ObjectNode) transform.path("translation");
    translation.put("x", 3113262).put("y", 676216);
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isFalse();
    translation.put("x", 3112278);
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isFalse();
    translation.put("x", 2996944);
    assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot)).isTrue();
  }

  @Test
  void narrowConcaveTabCannotPenetrateDeeplyIntoAnotherPiece() throws Exception {
    JsonNode vectors =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/local-geometry-v1.json").toFile());
    JsonNode contour = vectors.path("concaveCases").path("contour");
    ObjectNode tabPiece = mapper.createObjectNode();
    tabPiece.put("pieceId", "00000000-0000-4000-8000-000000000111");
    tabPiece.putObject("solvedPosition").put("x", 0).put("y", 0);
    ArrayNode edges = tabPiece.putArray("edges");
    for (int i = 0; i < contour.size(); i++) {
      JsonNode a = contour.get(i), b = contour.get((i + 1) % contour.size());
      ObjectNode edge = edges.addObject();
      edge.putObject("start").put("x", a.get(0).intValue()).put("y", a.get(1).intValue());
      ObjectNode line = edge.putArray("segments").addObject();
      line.put("kind", "line");
      line.putObject("to").put("x", b.get(0).intValue()).put("y", b.get(1).intValue());
    }
    for (JsonNode example : vectors.path("concaveCases").path("squareLeft")) {
      ObjectNode square = vectors.path("pieces").get(0).deepCopy();
      ((ObjectNode) square.path("solvedPosition")).put("x", example.path("x").intValue());
      ObjectNode definition = mapper.createObjectNode();
      definition.putArray("pieces").add(tabPiece).add(square);
      ObjectNode snapshot = mapper.createObjectNode();
      ArrayNode groups = snapshot.putArray("groups");
      for (JsonNode piece : definition.path("pieces")) {
        ObjectNode group = groups.addObject();
        group.putArray("pieceIds").add(piece.path("pieceId").textValue());
        ObjectNode transform = group.putObject("transform");
        transform.put("rotation", 0);
        transform.putObject("translation").put("x", 0).put("y", 0);
      }
      assertThat(LocalGeometry.hasForbiddenOverlap(definition, snapshot))
          .as("square left at " + example.path("x").intValue())
          .isEqualTo(example.path("forbidden").booleanValue());
    }
  }
}
