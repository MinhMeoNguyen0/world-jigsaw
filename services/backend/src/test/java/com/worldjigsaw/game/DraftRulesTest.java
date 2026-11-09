package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DraftRulesTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final DraftRules rules = new DraftRules(new BoardValidator());

  DraftRulesTest() throws Exception {}

  private JsonNode fixture(String name) throws Exception {
    return mapper.readTree(Path.of("../../packages/generator/fixtures/" + name).toFile());
  }

  private ObjectNode transform(int x, int y, int rotation) {
    ObjectNode t = mapper.createObjectNode();
    t.putObject("translation").put("x", x).put("y", y);
    t.put("rotation", rotation);
    return t;
  }

  private String id(JsonNode group) {
    return group.path("groupId").textValue();
  }

  @Test
  void generatedBoardsRespectQuotaAdjustmentUndoAndStaleGroups() throws Exception {
    for (String name : new String[] {"review-60.json", "sample-80.json"}) {
      JsonNode board = fixture(name),
          definition = board.path("definition"),
          snapshot = board.path("snapshot");
      DraftRules.Draft draft = rules.begin(definition, snapshot, 5);
      java.util.List<JsonNode> outside = new java.util.ArrayList<>();
      for (JsonNode group : snapshot.path("groups"))
        if (!group.path("finalPosition").booleanValue()) outside.add(group);
      assertThat(outside.size()).isGreaterThanOrEqualTo(6);
      String firstAction = UUID.randomUUID().toString();
      for (int i = 0; i < 5; i++)
        draft =
            rules.move(
                draft,
                id(outside.get(i)),
                i == 0 ? firstAction : UUID.randomUUID().toString(),
                transform(0, 0, 0));
      assertThat(rules.pendingCount(draft)).isEqualTo(5);
      DraftRules.Draft five = draft;
      assertThatThrownBy(() ->
              rules.move(
                  five, id(outside.get(5)), UUID.randomUUID().toString(), transform(0, 0, 0)))
          .isInstanceOf(DraftRules.RuleError.class)
          .hasMessageContaining("allowance");
      draft = rules.move(draft, id(outside.getFirst()), firstAction, transform(100, 0, 0));
      assertThat(rules.pendingCount(draft)).isEqualTo(5);
      DraftRules.Confirmation confirmation = rules.confirm(draft, snapshot, 5);
      assertThat(confirmation.charged()).isEqualTo(5);
      assertThat(confirmation.snapshot().path("boardVersion").longValue())
          .isEqualTo(snapshot.path("boardVersion").longValue() + 1);
      ObjectNode stale = snapshot.deepCopy();
      for (JsonNode group : stale.path("groups"))
        if (id(group).equals(id(outside.getFirst())))
          ((ObjectNode) group).put("revision", group.path("revision").longValue() + 1);
      DraftRules.Draft pending = draft;
      assertThatThrownBy(() -> rules.confirm(pending, stale, 5))
          .isInstanceOf(DraftRules.RuleError.class)
          .hasMessageContaining("changed");
      DraftRules.Draft undone = rules.undo(draft, firstAction);
      assertThat(rules.generation(undone)).isEqualTo(1);
      assertThat(rules.pendingCount(undone)).isEqualTo(4);
      assertThat(rules.pendingCount(draft)).isEqualTo(5);
    }
  }

  @Test
  void connectedGroupMovesAsOneAndWrongNeighborsDoNotAttach() throws Exception {
    JsonNode board = fixture("review-60.json"), snapshot = board.path("snapshot");
    JsonNode a = snapshot.path("groups").get(0),
        b = snapshot.path("groups").get(1),
        wrong = snapshot.path("groups").get(2);
    String action = UUID.randomUUID().toString(), merged = UUID.randomUUID().toString();
    DraftRules.Draft draft = rules.begin(board.path("definition"), snapshot, 5);
    draft = rules.move(draft, id(a), action, transform(0, 0, 0));
    DraftRules.Draft pending = draft;
    assertThatThrownBy(() -> rules.attach(pending, id(a), id(wrong), UUID.randomUUID().toString()))
        .isInstanceOf(DraftRules.RuleError.class);
    draft = rules.attach(draft, id(a), id(b), merged);
    assertThat(rules.pendingCount(draft)).isZero();
    draft = rules.move(draft, merged, action, transform(100, 0, 0));
    assertThat(rules.pendingCount(draft)).isEqualTo(1);
    DraftRules.Draft undone = rules.undo(draft, action);
    assertThat(rules.actions(undone)).isEmpty();
    assertThat(rules.groups(undone)).hasSize(snapshot.path("groups").size());
    DraftRules.Confirmation result = rules.confirm(draft, snapshot, 5);
    assertThat(result.charged()).isEqualTo(1);
    assertThat(result.snapshot().path("groups").size())
        .isEqualTo(snapshot.path("groups").size() - 1);
    JsonNode group = null;
    for (JsonNode g : result.snapshot().path("groups")) if (id(g).equals(merged)) group = g;
    assertThat(group).isNotNull();
    assertThat(group.path("pieceIds").size()).isEqualTo(2);
    assertThat(group.path("transform").path("translation").path("x").intValue()).isEqualTo(100);
  }

  @Test
  void undoJoinedActionRestoresOtherDraftsAndLaterIndependentMoves() throws Exception {
    JsonNode board = fixture("review-60.json"), snapshot = board.path("snapshot");
    JsonNode first = snapshot.path("groups").get(0), second = snapshot.path("groups").get(1),
        third = snapshot.path("groups").get(2);
    String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString(),
        c = UUID.randomUUID().toString(), merged = UUID.randomUUID().toString();
    DraftRules.Draft draft = rules.begin(board.path("definition"), snapshot, 5);
    draft = rules.move(draft, id(first), a, transform(100, 0, 0));
    draft = rules.move(draft, id(second), b, transform(0, 0, 0));
    draft = rules.attach(draft, id(first), id(second), merged);
    draft = rules.move(draft, id(third), c, transform(200, 0, 0));
    DraftRules.Draft undone = rules.undo(draft, a);
    assertThat(rules.actions(undone)).extracting(DraftRules.ActionView::actionId).containsExactly(b, c);
    assertThat(rules.groupTransform(undone, id(second))).isEqualTo(transform(0, 0, 0));
    assertThat(rules.groupTransform(undone, id(third))).isEqualTo(transform(200, 0, 0));
    assertThat(rules.groupTransform(undone, id(first))).isEqualTo(first.path("transform"));
  }

  @Test
  void undoPreservesEachSurvivorsLatestJoinedAdjustment() throws Exception {
    JsonNode board = fixture("review-60.json"), snapshot = board.path("snapshot");
    JsonNode first = snapshot.path("groups").get(0), second = snapshot.path("groups").get(1);
    for (int rotation : new int[] {0, 90000, 180000, 270000}) {
      String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString(),
          merged = UUID.randomUUID().toString();
      DraftRules.Draft draft = rules.begin(board.path("definition"), snapshot, 5);
      draft = rules.move(draft, id(first), a, transform(0, 0, 0));
      draft = rules.move(draft, id(second), b, transform(0, 0, 0));
      draft = rules.attach(draft, id(first), id(second), merged);
      ObjectNode movedA = transform(500, -300, rotation);
      draft = rules.move(draft, merged, a, movedA);
      DraftRules.Draft undoneB = rules.undo(draft, b);
      assertThat(rules.groupTransform(undoneB, id(first))).isEqualTo(movedA);
      assertThat(rules.groupTransform(undoneB, id(second))).isEqualTo(second.path("transform"));
      assertThat(rules.pendingCount(undoneB)).isEqualTo(1);
      assertThat(rules.confirm(undoneB, snapshot, 5).charged()).isEqualTo(1);
      DraftRules.Draft undoneA = rules.undo(draft, a);
      assertThat(rules.groupTransform(undoneA, id(second))).isEqualTo(transform(0, 0, 0));

      ObjectNode movedB = transform(-700, 900, rotation);
      draft = rules.move(draft, merged, b, movedB);
      assertThat(rules.groupTransform(rules.undo(draft, b), id(first))).isEqualTo(movedA);
      assertThat(rules.groupTransform(rules.undo(draft, a), id(second))).isEqualTo(movedB);
    }
  }

  @Test
  void joinedAdjustmentSurvivesSplittingNestedJoins() throws Exception {
    JsonNode board = fixture("review-60.json"), snapshot = board.path("snapshot");
    JsonNode first = snapshot.path("groups").get(0), second = snapshot.path("groups").get(1),
        third = snapshot.path("groups").get(2);
    String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString(),
        c = UUID.randomUUID().toString(), pair = UUID.randomUUID().toString(),
        triple = UUID.randomUUID().toString();
    DraftRules.Draft draft = rules.begin(board.path("definition"), snapshot, 5);
    draft = rules.move(draft, id(first), a, transform(0, 0, 0));
    draft = rules.move(draft, id(second), b, transform(0, 0, 0));
    draft = rules.move(draft, id(third), c, transform(0, 0, 0));
    draft = rules.attach(draft, id(first), id(second), pair);
    draft = rules.attach(draft, pair, id(third), triple);
    ObjectNode moved = transform(1500, -2500, 0);
    draft = rules.move(draft, triple, a, moved);
    DraftRules.Draft undone = rules.undo(draft, b);
    assertThat(rules.actions(undone)).extracting(DraftRules.ActionView::actionId).containsExactly(a, c);
    assertThat(rules.groupTransform(undone, id(first))).isEqualTo(moved);
    assertThat(rules.groupTransform(undone, id(third))).isEqualTo(transform(0, 0, 0));
  }

  @Test
  void equalTransformReplayPreservesSurvivorAcrossNestedUndo() throws Exception {
    JsonNode board = fixture("review-60.json"), snapshot = board.path("snapshot");
    JsonNode first = snapshot.path("groups").get(0), second = snapshot.path("groups").get(1),
        third = snapshot.path("groups").get(2);
    for (int rotation : new int[] {0, 90000, 180000, 270000}) {
      for (boolean reverse : new boolean[] {false, true}) {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString(),
            c = UUID.randomUUID().toString(), pair = UUID.randomUUID().toString(),
            triple = UUID.randomUUID().toString();
        String removed = reverse ? a : b, survivor = reverse ? b : a;
        JsonNode removedGroup = reverse ? first : second, survivorGroup = reverse ? second : first;
        ObjectNode base = transform(0, 0, rotation);
        int direction = reverse ? -1 : 1;
        ObjectNode moved = transform(direction * 1300, direction * -700, rotation);
        DraftRules.Draft draft = rules.begin(board.path("definition"), snapshot, 5);
        draft = rules.move(draft, id(first), a, base);
        draft = rules.move(draft, id(second), b, base);
        draft = rules.move(draft, id(third), c, base);
        draft = rules.attach(draft, id(first), id(second), pair);
        draft = rules.move(draft, pair, removed, moved);
        DraftRules.Draft unchanged = rules.move(draft, pair, survivor, moved);
        assertThat(rules.groupTransform(rules.undo(unchanged, removed), id(survivorGroup)))
            .isEqualTo(base);
        draft = rules.attach(draft, pair, id(third), triple);
        draft = rules.move(draft, triple, survivor, moved);
        DraftRules.Draft undone = rules.undo(draft, removed);
        assertThat(rules.groupTransform(undone, id(survivorGroup))).isEqualTo(moved);
        assertThat(rules.groupTransform(undone, id(removedGroup)))
            .isEqualTo(removedGroup.path("transform"));
        assertThat(rules.groupTransform(undone, id(third))).isEqualTo(base);
        int charged = rotation == 0 ? 1 : 2;
        assertThat(rules.pendingCount(undone)).isEqualTo(charged);
        assertThat(rules.confirm(undone, snapshot, 5).charged()).isEqualTo(charged);
      }
    }
  }

  @Test
  void freeOuterSortAndLegacyRejectionLeaveInputsUnchanged() throws Exception {
    JsonNode board = fixture("review-60.json"),
        snapshot = board.path("snapshot"),
        outer = snapshot.path("groups").get(3);
    ObjectNode adjusted = outer.path("transform").deepCopy();
    ObjectNode translation = (ObjectNode) adjusted.path("translation");
    translation.put("x", translation.path("x").intValue() + 100);
    DraftRules.Draft free =
        rules.move(
            rules.begin(board.path("definition"), snapshot, 0),
            id(outer),
            UUID.randomUUID().toString(),
            adjusted);
    assertThat(rules.pendingCount(free)).isZero();
    assertThat(rules.confirm(free, snapshot, 0).charged()).isZero();
    ObjectNode legacy = snapshot.deepCopy();
    ((ObjectNode) legacy.path("groups").get(0).path("transform")).put("rotation", 12345);
    String before = legacy.toString();
    DraftRules.Draft legacyDraft = rules.begin(board.path("definition"), legacy, 5);
    assertThatThrownBy(() ->
            rules.move(
                legacyDraft,
                id(legacy.path("groups").get(0)),
                UUID.randomUUID().toString(),
                transform(0, 0, 0)))
        .isInstanceOf(DraftRules.RuleError.class)
        .hasMessageContaining("Legacy");
    assertThat(legacy.toString()).isEqualTo(before);
  }

  @Test
  void independentlyIntroducedPiecesKeepBothChargesAfterJoining() throws Exception {
    JsonNode board = fixture("sample-80.json"), snapshot = board.path("snapshot");
    JsonNode first = snapshot.path("groups").get(0), second = snapshot.path("groups").get(1);
    String a = UUID.randomUUID().toString(),
        b = UUID.randomUUID().toString(),
        merged = UUID.randomUUID().toString();
    DraftRules.Draft draft =
        rules.move(
            rules.begin(board.path("definition"), snapshot, 5), id(first), a, transform(0, 0, 0));
    draft = rules.move(draft, id(second), b, transform(0, 0, 0));
    assertThat(rules.pendingCount(draft)).isEqualTo(2);
    draft = rules.attach(draft, id(first), id(second), merged);
    assertThat(rules.pendingCount(draft)).isEqualTo(2);
    draft = rules.move(draft, merged, a, transform(100, 100, 0));
    assertThat(rules.confirm(draft, snapshot, 5).charged()).isEqualTo(2);
  }

  @Test
  void unrelatedNewerGroupSurvivesConfirmation() throws Exception {
    JsonNode board = fixture("review-60.json"), snapshot = board.path("snapshot");
    JsonNode moving = snapshot.path("groups").get(3);
    DraftRules.Draft draft =
        rules.move(
            rules.begin(board.path("definition"), snapshot, 5),
            id(moving),
            UUID.randomUUID().toString(),
            transform(0, 0, 0));
    ObjectNode latest = snapshot.deepCopy();
    ObjectNode unrelated = (ObjectNode) latest.path("groups").get(4);
    unrelated.put("revision", 1);
    ObjectNode translation = (ObjectNode) unrelated.path("transform").path("translation");
    translation.put("x", translation.path("x").intValue() + 100);
    latest.put("boardVersion", latest.path("boardVersion").longValue() + 1);
    JsonNode actual = rules.confirm(draft, latest, 5).snapshot();
    JsonNode found = null;
    for (JsonNode group : actual.path("groups")) if (id(group).equals(id(unrelated))) found = group;
    assertThat(found).isEqualTo(unrelated);
  }

  @Test
  void generatedQuarterTurnsCanBeCorrectedWithRepeatedControls() throws Exception {
    JsonNode board = fixture("sample-80.json"),
        snapshot = board.path("snapshot"),
        definition = board.path("definition");
    java.util.Set<Integer> tested = new java.util.HashSet<>();
    for (JsonNode group : snapshot.path("groups")) {
      int initial = group.path("transform").path("rotation").intValue();
      if (!tested.add(initial)) continue;
      JsonNode piece = null;
      for (JsonNode p : definition.path("pieces"))
        if (p.path("pieceId").equals(group.path("pieceIds").get(0))) piece = p;
      assertThat(piece).isNotNull();
      long x =
          piece.path("solvedPosition").path("x").longValue()
              + piece.path("referencePoint").path("x").longValue();
      long y =
          piece.path("solvedPosition").path("y").longValue()
              + piece.path("referencePoint").path("y").longValue();
      long rx =
          switch (initial) {
            case 0 -> x;
            case 90000 -> -y;
            case 180000 -> -x;
            case 270000 -> y;
            default -> throw new AssertionError("unsupported generator orientation");
          };
      long ry =
          switch (initial) {
            case 0 -> y;
            case 90000 -> x;
            case 180000 -> -y;
            case 270000 -> -x;
            default -> throw new AssertionError("unsupported generator orientation");
          };
      ObjectNode pivot =
          mapper
              .createObjectNode()
              .put("x", rx + group.path("transform").path("translation").path("x").longValue())
              .put("y", ry + group.path("transform").path("translation").path("y").longValue());
      DraftRules.Draft draft = rules.begin(definition, snapshot, 5);
      String action = UUID.randomUUID().toString();
      ObjectNode transform = group.path("transform").deepCopy();
      while (transform.path("rotation").intValue() != 0) {
        transform = DraftRules.rotateAround(transform, pivot, 90000);
        draft = rules.move(draft, id(group), action, transform);
        assertThat(rules.pendingCount(draft)).isZero();
      }
      draft = rules.move(draft, id(group), action, transform(0, 0, 0));
      assertThat(rules.pendingCount(draft)).isEqualTo(1);
      JsonNode result = rules.confirm(draft, snapshot, 5).snapshot();
      JsonNode moved = null;
      for (JsonNode candidate : result.path("groups"))
        if (id(candidate).equals(id(group))) moved = candidate;
      assertThat(moved).isNotNull();
      assertThat(moved.path("transform").path("rotation").intValue()).isZero();
    }
    assertThat(tested).containsExactlyInAnyOrder(0, 90000, 180000, 270000);
  }

  @Test
  void quarterTurnArithmeticMatchesSharedRulesVectors() throws Exception {
    JsonNode vectors =
        mapper.readTree(Path.of("../../packages/rules/vectors/behavior.json").toFile());
    for (JsonNode vector : vectors.path("rotation")) {
      ObjectNode input = mapper.createObjectNode();
      input.set("translation", vector.path("translation"));
      input.set("rotation", vector.path("rotation"));
      JsonNode actual =
          DraftRules.rotateAround(input, vector.path("pivot"), vector.path("delta").intValue());
      assertThat(actual.toString()).isEqualTo(vector.path("expected").toString());
    }
  }

  @Test
  void portableDraftScenariosMatchSharedTypeScriptOracle() throws Exception {
    JsonNode vectors =
        mapper.readTree(Path.of("../../packages/rules/vectors/draft-v1.json").toFile());
    for (JsonNode scenario : vectors.path("scenarios")) {
      JsonNode board = fixture(scenario.path("fixture").textValue()),
          snapshot = board.path("snapshot");
      DraftRules.Draft draft = rules.begin(board.path("definition"), snapshot, 5);
      for (JsonNode operation : scenario.path("operations")) {
        String kind = operation.path("kind").textValue();
        switch (kind) {
          case "move" ->
              draft =
                  rules.move(
                      draft,
                      groupRef(snapshot, operation.path("group").textValue()),
                      operation.path("action").textValue(),
                      transform(
                          operation.path("x").intValue(),
                          operation.path("y").intValue(),
                          operation.path("rotation").intValue()));
          case "attach" ->
              draft =
                  rules.attach(
                      draft,
                      groupRef(snapshot, operation.path("group").textValue()),
                      groupRef(snapshot, operation.path("target").textValue()),
                      operation.path("merged").textValue());
          case "undo" -> draft = rules.undo(draft, operation.path("action").textValue());
          default -> throw new AssertionError("unknown operation " + kind);
        }
        assertThat(rules.pendingCount(draft))
            .as(scenario.path("name").textValue())
            .isEqualTo(operation.path("pending").intValue());
      }
      DraftRules.Confirmation result = rules.confirm(draft, snapshot, 5);
      assertThat(result.charged()).isEqualTo(scenario.path("charged").intValue());
      assertThat(result.snapshot().path("groups").size())
          .isEqualTo(snapshot.path("groups").size() + scenario.path("groupDelta").intValue());
      assertThat(rules.generation(draft)).isEqualTo(scenario.path("generation").intValue());
    }
  }

  private String groupRef(JsonNode snapshot, String value) {
    return value.matches("[0-9]+")
        ? id(snapshot.path("groups").get(Integer.parseInt(value)))
        : value;
  }
}
