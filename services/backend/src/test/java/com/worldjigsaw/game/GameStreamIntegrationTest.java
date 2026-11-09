package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(GameStreamIntegrationTest.ClockConfig.class)
class GameStreamIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  @LocalServerPort int port;
  @Autowired GameBoards boards;
  @Autowired GameWireValidator validator;
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
  void twoIndependentClientsPreviewCancelUndoConfirmAndReconnect() throws Exception {
    JsonNode board =
        mapper.readTree(Path.of("../../packages/generator/fixtures/sample-80.json").toFile());
    JsonNode snapshot = board.path("snapshot"),
        g0 = snapshot.path("groups").get(0),
        g1 = snapshot.path("groups").get(1);
    boards.importBoard(LocalDate.parse("2026-11-07"), board.path("definition"), snapshot);
    String puzzle = snapshot.path("puzzleId").textValue(), origin = "http://127.0.0.1:" + port;
    URI sessionUri = URI.create(origin + "/api/v1/session"),
        socketUri = URI.create("ws://127.0.0.1:" + port + "/api/v1/game/stream");
    HttpClient a = HttpClient.newHttpClient(), b = HttpClient.newHttpClient();
    String cookieA = cookie(a, sessionUri, origin), cookieB = cookie(b, sessionUri, origin);
    assertThrows(
        ExecutionException.class,
        () ->
            a.newWebSocketBuilder()
                .header("Origin", origin)
                .buildAsync(socketUri, new Listener())
                .get());
    assertThrows(
        ExecutionException.class,
        () ->
            a.newWebSocketBuilder()
                .header("Origin", "http://attacker.test")
                .header("Cookie", cookieA)
                .buildAsync(socketUri, new Listener())
                .get());
    Listener la = new Listener(), lb = new Listener();
    WebSocket sa =
        a.newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookieA)
            .buildAsync(socketUri, la)
            .get();
    WebSocket sb =
        b.newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookieB)
            .buildAsync(socketUri, lb)
            .get();
    ObjectNode subscribe =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "subscribe")
            .put("puzzleId", puzzle)
            .putNull("afterBoardVersion");
    send(sa, subscribe);
    send(sb, subscribe);
    JsonNode initialA = la.next(), initialB = lb.next();
    assertThat(initialA.path("type").asText()).isEqualTo("subscribed");
    assertThat(initialA.path("snapshot")).isEqualTo(initialB.path("snapshot"));
    assertThat(initialA.path("watermark").longValue())
        .isEqualTo(snapshot.path("boardVersion").longValue());
    JsonNode presence = get(a, origin + "/api/v1/game/presence?puzzleId=" + puzzle, cookieA);
    assertSchema("PresenceSnapshot", presence);
    assertThat(presence.path("players").size()).isEqualTo(2);
    ObjectNode unavailableSubscription = subscribe.deepCopy();
    unavailableSubscription.put("puzzleId", UUID.randomUUID().toString());
    send(sa, unavailableSubscription);
    assertThat(la.next().path("error").path("code").asText()).isEqualTo("PUZZLE_UNAVAILABLE");
    JsonNode turnA = post(a, origin + "/api/v1/game/turns", origin, cookieA, begin(puzzle));
    JsonNode turnB = post(b, origin + "/api/v1/game/turns", origin, cookieB, begin(puzzle));
    assertSchema("Turn", turnA);
    assertSchema("Turn", turnB);
    String idA = turnA.path("turnId").textValue(), idB = turnB.path("turnId").textValue();
    JsonNode emptySummary = get(a, origin + "/api/v1/game/turns/" + idA + "/summary", cookieA);
    assertSchema("DraftSummary", emptySummary);
    assertThat(emptySummary.path("pendingMoves").intValue()).isZero();
    String actionA = UUID.randomUUID().toString(), actionB = UUID.randomUUID().toString();
    JsonNode firstFrame = preview(puzzle, idA, 0, 1, action(actionA, g0, 0, 0));
    validator.parse("PreviewSubmit", firstFrame.toString());
    send(sa, firstFrame);
    JsonNode firstReceived = la.next();
    assertPreview(firstReceived, idA);
    assertPreview(lb.next(), idA);
    JsonNode firstSummary = get(a, origin + "/api/v1/game/turns/" + idA + "/summary", cookieA);
    assertSchema("DraftSummary", firstSummary);
    assertThat(firstSummary.path("draftRevision").longValue()).isEqualTo(1);
    assertThat(firstSummary.path("pendingMoves").intValue()).isEqualTo(1);
    JsonNode readDuring = get(a, origin + "/api/v1/game/current", cookieA);
    assertThat(readDuring.path("snapshot").path("boardVersion").longValue())
        .isEqualTo(snapshot.path("boardVersion").longValue());
    assertThat(readDuring.path("allowance").path("used").intValue()).isZero();
    assertThat(readDuring.path("ownTurn").path("turnId").asText()).isEqualTo(idA);
    send(sb, preview(puzzle, idB, 0, 1, action(actionB, g0, 0, 0)));
    assertThat(lb.next().path("error").path("code").asText()).isEqualTo("PIECE_BUSY");
    send(sb, preview(puzzle, idB, 0, 2, action(actionB, g1, 0, 0)));
    assertPreview(la.next(), idB);
    assertPreview(lb.next(), idB);
    ObjectNode cancelA = request(puzzle, idA, 0, 0);
    JsonNode canceled =
        post(a, origin + "/api/v1/game/turns/" + idA + "/cancel", origin, cookieA, cancelA);
    assertSchema("CancelResult", canceled);
    assertThat(canceled.path("outcome").asText()).isEqualTo("canceled");
    assertThat(post(a, origin + "/api/v1/game/turns/" + idA + "/cancel", origin, cookieA, cancelA))
        .isEqualTo(canceled);
    assertClear(la.next(), idA, "canceled");
    assertClear(lb.next(), idA, "canceled");
    ObjectNode undo = request(puzzle, idB, 0, 0).put("actionId", actionB).put("draftRevision", 1);
    JsonNode undone =
        post(b, origin + "/api/v1/game/turns/" + idB + "/undo", origin, cookieB, undo);
    assertSchema("UndoResult", undone);
    assertThat(undone.path("generation").intValue()).isEqualTo(1);
    assertClear(la.next(), idB, "undone");
    assertClear(lb.next(), idB, "undone");
    send(sb, preview(puzzle, idB, 0, 3, action(actionB, g1, 0, 0)));
    assertThat(lb.next().path("error").path("code").asText()).isEqualTo("TURN_CLOSED");

    String a1 = UUID.randomUUID().toString(), a2 = UUID.randomUUID().toString();
    Map<String, JsonNode> latest = new TreeMap<>();
    send(sb, preview(puzzle, idB, 1, 1, action(a1, g0, 0, 0)));
    assertPreview(la.next(), idB);
    record(latest, lb.next());
    send(sb, preview(puzzle, idB, 1, 2, action(a2, g1, 0, 0)));
    assertPreview(la.next(), idB);
    assertPreview(la.next(), idB);
    record(latest, lb.next());
    record(latest, lb.next());
    ObjectNode join = action(a1, g0, 0, 0);
    ((ArrayNode) join.path("affectedPieceIds")).add(piece(g1));
    ((ArrayNode) join.path("baseRevisions")).add(base(g1));
    send(sb, preview(puzzle, idB, 1, 3, join));
    assertPreview(la.next(), idB);
    assertPreview(la.next(), idB);
    record(latest, lb.next());
    record(latest, lb.next());
    assertThat(latest).hasSize(2);
    assertThat(latest.get(a1).path("affectedPieceIds").size()).isEqualTo(2);
    Listener extra = new Listener();
    WebSocket reconnectWhileActive =
        b.newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookieB)
            .buildAsync(socketUri, extra)
            .get();
    ObjectNode withCursor = subscribe.deepCopy();
    withCursor.put("afterBoardVersion", snapshot.path("boardVersion").longValue());
    send(reconnectWhileActive, withCursor);
    JsonNode handoff = extra.next();
    assertThat(handoff.path("type").asText()).isEqualTo("subscribed");
    assertThat(
            get(a, origin + "/api/v1/game/presence?puzzleId=" + puzzle, cookieA)
                .path("players")
                .size())
        .isEqualTo(2);
    assertThat(handoff.path("previews").size()).isEqualTo(2);
    assertThat(handoff.path("previews").get(0).path("sequence").longValue())
        .isLessThan(handoff.path("previews").get(1).path("sequence").longValue());
    assertThat(handoff.path("previews").get(0).path("action").path("affectedPieceIds").size())
        .isEqualTo(2);
    assertThat(handoff.path("previews").get(1).path("action").path("affectedPieceIds").size())
        .isEqualTo(2);
    reconnectWhileActive.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
    String command = UUID.randomUUID().toString();
    ObjectNode confirm =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle)
            .put("turnId", idB)
            .put("commandId", command)
            .put("turnRevision", 1)
            .put("generation", 1);
    ArrayNode actions = confirm.putArray("actions");
    latest.values().forEach(actions::add);
    HttpRequest save =
        postRequest(origin + "/api/v1/game/turns/" + idB + "/confirm", origin, cookieB, confirm);
    var discardedReply = b.sendAsync(save, HttpResponse.BodyHandlers.ofString());
    assertPreview(la.next(), idB);
    assertPreview(lb.next(), idB);
    assertPreview(la.next(), idB);
    assertPreview(lb.next(), idB);
    JsonNode eventA = la.next(), eventB = lb.next();
    assertSchema("BoardChanged", eventA);
    assertThat(eventA.path("type").asText()).isEqualTo("boardChanged");
    assertThat(eventA).isEqualTo(eventB);
    assertClear(la.next(), idB, "confirmed");
    assertClear(lb.next(), idB, "confirmed");
    JsonNode saved = get(b, origin + "/api/v1/game/commands/" + command, cookieB);
    assertSchema("CommandResult", saved);
    assertThat(saved.path("outcome").asText()).isEqualTo("confirmed");
    assertThat(saved.path("allowance").path("used").intValue()).isEqualTo(2);
    assertThat(post(b, origin + "/api/v1/game/turns/" + idB + "/confirm", origin, cookieB, confirm))
        .isEqualTo(saved);
    discardedReply.get(5, TimeUnit.SECONDS);

    // Exercise real v1 move/preview/Confirm traffic for all four seeded orientations.
    java.util.Set<Integer> directions = new java.util.HashSet<>();
    java.util.Set<String> orientedGroups = new java.util.HashSet<>();
    int used = 0;
    for (int i = 2; i < snapshot.path("groups").size() && directions.size() < 4; i++) {
      JsonNode group = snapshot.path("groups").get(i);
      int initialRotation = group.path("transform").path("rotation").intValue();
      if (!directions.add(initialRotation)) continue;
      orientedGroups.add(group.path("groupId").textValue());
      JsonNode piece = null;
      for (JsonNode candidate : board.path("definition").path("pieces"))
        if (candidate.path("pieceId").equals(group.path("pieceIds").get(0))) piece = candidate;
      assertThat(piece).isNotNull();
      ObjectNode pivot = pivot(piece, group.path("transform"));
      JsonNode turn = post(a, origin + "/api/v1/game/turns", origin, cookieA, begin(puzzle));
      String turnId = turn.path("turnId").textValue(), actionId = UUID.randomUUID().toString();
      ObjectNode transform = group.path("transform").deepCopy();
      JsonNode latestAction = null;
      long sequence = 0;
      while (transform.path("rotation").intValue() != 0) {
        transform = DraftRules.rotateAround(transform, pivot, 90000);
        ObjectNode adjustment = action(actionId, group, 0, 0);
        adjustment.set("intendedTransform", transform.deepCopy());
        send(sa, preview(puzzle, turnId, 0, ++sequence, adjustment));
        JsonNode observed = la.next();
        assertPreview(observed, turnId);
        assertPreview(lb.next(), turnId);
        latestAction = observed.path("action");
      }
      send(sa, preview(puzzle, turnId, 0, ++sequence, action(actionId, group, 0, 0)));
      JsonNode placed = la.next();
      assertPreview(placed, turnId);
      assertPreview(lb.next(), turnId);
      latestAction = placed.path("action");
      assertThat(latestAction.path("intendedTransform").path("rotation").intValue()).isZero();
      assertThat(
              get(a, origin + "/api/v1/game/current", cookieA)
                  .path("allowance")
                  .path("used")
                  .intValue())
          .isEqualTo(used);
      ObjectNode placement =
          mapper
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("puzzleId", puzzle)
              .put("turnId", turnId)
              .put("commandId", UUID.randomUUID().toString())
              .put("turnRevision", 0)
              .put("generation", 0);
      placement.putArray("actions").add(latestAction);
      var savePlacement =
          a.sendAsync(
              postRequest(
                  origin + "/api/v1/game/turns/" + turnId + "/confirm", origin, cookieA, placement),
              HttpResponse.BodyHandlers.ofString());
      assertPreview(la.next(), turnId);
      assertPreview(lb.next(), turnId);
      assertThat(la.next().path("type").asText()).isEqualTo("boardChanged");
      assertThat(lb.next().path("type").asText()).isEqualTo("boardChanged");
      assertClear(la.next(), turnId, "confirmed");
      assertClear(lb.next(), turnId, "confirmed");
      var placedResponse = savePlacement.get(10, TimeUnit.SECONDS);
      assertThat(placedResponse.statusCode()).as(placedResponse.body()).isEqualTo(200);
      assertThat(mapper.readTree(placedResponse.body()).path("allowance").path("used").intValue())
          .isEqualTo(++used);
    }
    assertThat(directions).containsExactlyInAnyOrder(0, 90000, 180000, 270000);

    // Canceling one player's draft must preserve another player's newer committed move.
    java.util.List<JsonNode> untouched = new java.util.ArrayList<>();
    for (int i = 2; i < snapshot.path("groups").size() && untouched.size() < 2; i++) {
      JsonNode group = snapshot.path("groups").get(i);
      if (!orientedGroups.contains(group.path("groupId").textValue())) untouched.add(group);
    }
    assertThat(untouched).hasSize(2);
    JsonNode untouchedA = untouched.get(0), untouchedB = untouched.get(1);
    JsonNode concurrentA = post(a, origin + "/api/v1/game/turns", origin, cookieA, begin(puzzle));
    JsonNode concurrentB = post(b, origin + "/api/v1/game/turns", origin, cookieB, begin(puzzle));
    String concurrentIdA = concurrentA.path("turnId").textValue(),
        concurrentIdB = concurrentB.path("turnId").textValue();
    send(
        sa,
        preview(
            puzzle, concurrentIdA, 0, 1, action(UUID.randomUUID().toString(), untouchedA, 0, 0)));
    assertPreview(la.next(), concurrentIdA);
    assertPreview(lb.next(), concurrentIdA);
    send(
        sb,
        preview(
            puzzle, concurrentIdB, 0, 1, action(UUID.randomUUID().toString(), untouchedB, 0, 0)));
    assertPreview(la.next(), concurrentIdB);
    JsonNode concurrentPreviewB = lb.next();
    assertPreview(concurrentPreviewB, concurrentIdB);
    ObjectNode otherConfirm =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle)
            .put("turnId", concurrentIdB)
            .put("commandId", UUID.randomUUID().toString())
            .put("turnRevision", 0)
            .put("generation", 0);
    otherConfirm.putArray("actions").add(concurrentPreviewB.path("action"));
    var otherSave =
        b.sendAsync(
            postRequest(
                origin + "/api/v1/game/turns/" + concurrentIdB + "/confirm",
                origin,
                cookieB,
                otherConfirm),
            HttpResponse.BodyHandlers.ofString());
    assertPreview(la.next(), concurrentIdB);
    assertPreview(lb.next(), concurrentIdB);
    assertThat(la.next().path("type").asText()).isEqualTo("boardChanged");
    assertThat(lb.next().path("type").asText()).isEqualTo("boardChanged");
    assertClear(la.next(), concurrentIdB, "confirmed");
    assertClear(lb.next(), concurrentIdB, "confirmed");
    assertThat(otherSave.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    JsonNode concurrentCancel =
        post(
            a,
            origin + "/api/v1/game/turns/" + concurrentIdA + "/cancel",
            origin,
            cookieA,
            request(puzzle, concurrentIdA, 0, 0));
    assertThat(concurrentCancel.path("outcome").asText()).isEqualTo("canceled");
    assertClear(la.next(), concurrentIdA, "canceled");
    assertClear(lb.next(), concurrentIdA, "canceled");
    JsonNode after = get(a, origin + "/api/v1/game/current", cookieA);
    JsonNode actualA = findGroup(after.path("snapshot"), untouchedA.path("groupId").textValue());
    JsonNode actualB = findGroup(after.path("snapshot"), untouchedB.path("groupId").textValue());
    assertThat(actualA.path("transform")).isEqualTo(untouchedA.path("transform"));
    assertThat(actualB.path("transform").path("rotation").intValue()).isZero();
    assertThat(actualB.path("transform").path("translation").path("x").intValue()).isZero();
    assertThat(actualB.path("transform").path("translation").path("y").intValue()).isZero();
    assertThat(after.path("snapshot").path("boardVersion").longValue())
        .isEqualTo(snapshot.path("boardVersion").longValue() + 6);
    assertThat(after.path("snapshot").path("groups").size())
        .isEqualTo(snapshot.path("groups").size() - 1);

    sb.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
    Listener reconnect = new Listener();
    WebSocket sc =
        b.newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookieB)
            .buildAsync(socketUri, reconnect)
            .get();
    send(sc, subscribe);
    JsonNode recovered = reconnect.next();
    assertThat(recovered.path("snapshot")).isEqualTo(after.path("snapshot"));
    assertThat(recovered.path("previews")).isEmpty();
    versionedSameSessionFence(origin, puzzle, after.path("snapshot"));
    sa.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
    sc.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
  }

  private void versionedSameSessionFence(String origin, String puzzle, JsonNode snapshot)
      throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String cookie = cookie(client, URI.create(origin + "/api/v1/session"), origin);
    URI socketUri = URI.create("ws://127.0.0.1:" + port + "/api/v1/game/stream");
    Listener first = new Listener(), second = new Listener();
    WebSocket a =
        client
            .newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookie)
            .buildAsync(socketUri, first)
            .get();
    WebSocket b =
        client
            .newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", cookie)
            .buildAsync(socketUri, second)
            .get();
    ObjectNode subscribe =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "subscribe.v2")
            .put("puzzleId", puzzle)
            .putNull("afterBoardVersion");
    send(a, subscribe);
    send(b, subscribe);
    JsonNode initial = first.next();
    assertSchema("SubscribedV2", initial);
    assertThat(initial.path("ownTurn").isNull()).isTrue();
    assertThat(initial.path("allowance").path("used").intValue()).isZero();
    second.next();
    JsonNode turn = post(client, origin + "/api/v1/game/turns", origin, cookie, begin(puzzle));
    assertThat(first.next().path("type").asText()).isEqualTo("resyncRequired");
    assertThat(second.next().path("type").asText()).isEqualTo("resyncRequired");
    send(a, subscribe);
    send(b, subscribe);
    JsonNode handoff = first.next();
    second.next();
    assertThat(handoff.path("ownTurn").path("turnId")).isEqualTo(turn.path("turnId"));
    assertThat(handoff.path("draftRevision").intValue()).isZero();
    JsonNode group = null;
    for (JsonNode candidate : snapshot.path("groups"))
      if (candidate.path("pieceIds").size() == 1
          && !candidate.path("finalPosition").booleanValue()
          && !candidate
              .path("transform")
              .path("translation")
              .equals(mapper.createObjectNode().put("x", 0).put("y", 0))) {
        group = candidate;
        break;
      }
    assertThat(group).isNotNull();
    String actionId = UUID.randomUUID().toString(), turnId = turn.path("turnId").asText();
    JsonNode submission = preview(puzzle, turnId, 0, 0, action(actionId, group, 0, 0));
    send(a, submission);
    assertPreview(first.next(), turnId);
    JsonNode ack = first.next();
    assertSchema("PreviewAcceptedV2", ack);
    assertThat(ack.path("sequence").intValue()).isZero();
    assertThat(ack.path("draftRevision").intValue()).isEqualTo(1);
    assertThat(second.next().path("type").asText()).isEqualTo("resyncRequired");
    send(b, subscribe);
    JsonNode recovered = second.next();
    assertThat(recovered.path("draftRevision").intValue()).isEqualTo(1);
    assertThat(recovered.path("actions")).isEqualTo(ack.path("actions"));
    ObjectNode repeated = submission.deepCopy();
    repeated.put("sequence", 1);
    send(b, repeated);
    assertThat(first.next().path("type").asText()).isEqualTo("resyncRequired");
    assertPreview(second.next(), turnId);
    JsonNode ack2 = second.next();
    assertThat(ack2.path("actions")).isEqualTo(ack.path("actions"));
    assertThat(ack2.path("draftRevision").intValue()).isEqualTo(2);
    ObjectNode staleUndo =
        request(puzzle, turnId, 0, 0).put("actionId", actionId).put("draftRevision", 1);
    var rejectedUndo =
        client.send(
            postRequest(
                origin + "/api/v1/game/turns/" + turnId + "/undo", origin, cookie, staleUndo),
            HttpResponse.BodyHandlers.ofString());
    assertThat(rejectedUndo.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(rejectedUndo.body()).path("error").path("code").asText())
        .isEqualTo("STALE_REVISION");
    ObjectNode legacyUndo = staleUndo.deepCopy();
    legacyUndo.remove("draftRevision");
    assertThat(
            client
                .send(
                    postRequest(
                        origin + "/api/v1/game/turns/" + turnId + "/undo",
                        origin,
                        cookie,
                        legacyUndo),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(400);
    send(a, subscribe);
    JsonNode afterRejectedUndo = first.next();
    assertThat(afterRejectedUndo.path("draftRevision").intValue()).isEqualTo(2);
    assertThat(afterRejectedUndo.path("actions")).isEqualTo(ack2.path("actions"));
    ObjectNode stale =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle)
            .put("turnId", turnId)
            .put("commandId", UUID.randomUUID().toString())
            .put("turnRevision", 0)
            .put("generation", 0)
            .put("draftRevision", 1);
    stale.set("actions", ack.path("actions"));
    var rejected =
        client.send(
            postRequest(
                origin + "/api/v1/game/turns/" + turnId + "/confirm", origin, cookie, stale),
            HttpResponse.BodyHandlers.ofString());
    assertThat(rejected.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(rejected.body()).path("error").path("code").asText())
        .isEqualTo("TURN_CLOSED");
    assertThat(
            get(client, origin + "/api/v1/game/current", cookie)
                .path("allowance")
                .path("used")
                .intValue())
        .isZero();
    ObjectNode confirm = stale.deepCopy();
    confirm.put("commandId", UUID.randomUUID().toString()).put("draftRevision", 2);
    JsonNode result =
        post(client, origin + "/api/v1/game/turns/" + turnId + "/confirm", origin, cookie, confirm);
    assertThat(result.path("outcome").asText()).isEqualTo("confirmed");
    assertThat(result.path("allowance").path("used").intValue()).isEqualTo(1);
    assertThat(
            post(
                client,
                origin + "/api/v1/game/turns/" + turnId + "/confirm",
                origin,
                cookie,
                confirm))
        .isEqualTo(result);
    a.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
    b.sendClose(WebSocket.NORMAL_CLOSURE, "done").get();
  }

  private JsonNode findGroup(JsonNode snapshot, String id) {
    for (JsonNode group : snapshot.path("groups"))
      if (id.equals(group.path("groupId").textValue())) return group;
    throw new AssertionError("group not found");
  }

  private ObjectNode pivot(JsonNode piece, JsonNode transform) {
    long x =
        piece.path("solvedPosition").path("x").longValue()
            + piece.path("referencePoint").path("x").longValue();
    long y =
        piece.path("solvedPosition").path("y").longValue()
            + piece.path("referencePoint").path("y").longValue();
    int angle = transform.path("rotation").intValue();
    long rx =
        switch (angle) {
          case 0 -> x;
          case 90000 -> -y;
          case 180000 -> -x;
          case 270000 -> y;
          default -> throw new AssertionError("unexpected orientation");
        };
    long ry =
        switch (angle) {
          case 0 -> y;
          case 90000 -> x;
          case 180000 -> -y;
          case 270000 -> -x;
          default -> throw new AssertionError("unexpected orientation");
        };
    return mapper
        .createObjectNode()
        .put("x", rx + transform.path("translation").path("x").longValue())
        .put("y", ry + transform.path("translation").path("y").longValue());
  }

  private void assertSchema(String type, JsonNode value) throws Exception {
    ObjectNode schema =
        (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/v1/schema.json").toFile());
    schema.remove("oneOf");
    schema.put("$ref", "#/$defs/" + type);
    var compiled =
        com.networknt.schema.JsonSchemaFactory.getInstance(
                com.networknt.schema.SpecVersion.VersionFlag.V7)
            .getSchema(schema);
    assertThat(compiled.validate(value)).as(type + ": " + value).isEmpty();
  }

  private ObjectNode begin(String puzzle) {
    return mapper
        .createObjectNode()
        .put("schemaVersion", 1)
        .put("puzzleId", puzzle)
        .put("requestId", UUID.randomUUID().toString());
  }

  private ObjectNode request(String puzzle, String turn, long generation, long revision) {
    return mapper
        .createObjectNode()
        .put("schemaVersion", 1)
        .put("puzzleId", puzzle)
        .put("turnId", turn)
        .put("requestId", UUID.randomUUID().toString())
        .put("turnRevision", revision)
        .put("generation", generation);
  }

  private String piece(JsonNode group) {
    return group.path("pieceIds").get(0).textValue();
  }

  private ObjectNode base(JsonNode group) {
    return mapper
        .createObjectNode()
        .put("groupId", group.path("groupId").textValue())
        .put("revision", group.path("revision").longValue());
  }

  private ObjectNode action(String actionId, JsonNode group, int x, int y) {
    ObjectNode a =
        mapper
            .createObjectNode()
            .put("actionId", actionId)
            .put("kind", "piece")
            .put("targetId", piece(group));
    a.putArray("affectedPieceIds").add(piece(group));
    a.putArray("baseRevisions").add(base(group));
    ObjectNode t = a.putObject("intendedTransform");
    t.putObject("translation").put("x", x).put("y", y);
    t.put("rotation", 0);
    return a;
  }

  private ObjectNode preview(
      String puzzle, String turn, long generation, long sequence, JsonNode action) {
    ObjectNode p =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "preview.submit")
            .put("puzzleId", puzzle)
            .put("turnId", turn)
            .put("generation", generation)
            .put("sequence", sequence);
    p.set("action", action);
    return p;
  }

  private void send(WebSocket socket, JsonNode payload) throws Exception {
    socket.sendText(payload.toString(), true).get(5, TimeUnit.SECONDS);
  }

  private void assertPreview(JsonNode event, String turn) {
    assertThat(event.path("type").asText()).as(event.toString()).isEqualTo("preview");
    assertThat(event.path("turnId").asText()).isEqualTo(turn);
  }

  private void assertClear(JsonNode event, String turn, String reason) {
    assertThat(event.path("type").asText()).isEqualTo("previewCleared");
    assertThat(event.path("turnId").asText()).isEqualTo(turn);
    assertThat(event.path("reason").asText()).isEqualTo(reason);
  }

  private void record(Map<String, JsonNode> actions, JsonNode preview) {
    assertThat(preview.path("type").asText()).isEqualTo("preview");
    JsonNode action = preview.path("action");
    actions.put(action.path("actionId").textValue(), action.deepCopy());
  }

  private String cookie(HttpClient client, URI uri, String origin) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(uri)
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
  }

  private HttpRequest postRequest(String url, String origin, String cookie, JsonNode body) {
    return HttpRequest.newBuilder(URI.create(url))
        .header("Origin", origin)
        .header("Cookie", cookie)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();
  }

  private JsonNode post(HttpClient client, String url, String origin, String cookie, JsonNode body)
      throws Exception {
    var response =
        client.send(postRequest(url, origin, cookie, body), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private JsonNode get(HttpClient client, String url, String cookie) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private final class Listener implements WebSocket.Listener {
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      socket.request(1);
      return null;
    }

    JsonNode next() throws Exception {
      String text = messages.poll(15, TimeUnit.SECONDS);
      if (text == null) throw new AssertionError("WebSocket reply timed out");
      return mapper.readTree(text);
    }
  }
}
