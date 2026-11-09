package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Single-process, internal coordinator. Network publication is not wired yet. */
@Service
class GameCoordinator {
  private static final int MAX_JOIN_STATES = 256;
  private final ObjectMapper mapper = new ObjectMapper();
  private final GameBoards boards;
  private final DraftRules rules;
  private final TurnLeaseRegistry leases;
  private final GameIdentityLedger identities;
  private final ConfirmationJournal journal;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final GameWriterGuard writer;
  private GameShapeService shapes;
  private final Map<UUID, Turn> turns = new HashMap<>();
  private final List<JsonNode> events = new ArrayList<>();
  private boolean resyncRequired;
  private java.time.LocalDate currentDay;
  private final Map<UUID, Subscriber> subscribers = new HashMap<>();
  private final Set<UUID> seenSubjects = new HashSet<>();
  private UUID recoveryCursor;

  interface Sink {
    boolean offer(JsonNode message);

    void slow();
  }

  private record Subscriber(UUID subject, UUID puzzle, Sink sink, boolean v2) {}

  private record PendingReceipt(UUID request, String kind, JsonNode payload, Runnable apply) {}

  record Started(
      UUID puzzle,
      UUID turnId,
      long turnRevision,
      long generation,
      Instant expiresAt,
      long boardVersion) {}

  record Changed(List<PreviewGeneration.Preview> previews, int pending) {}

  record Subscription(JsonNode snapshot, List<PreviewGeneration.Preview> previews) {}

  record Events(List<JsonNode> values, boolean resyncRequired) {}

  private static final class Turn {
    final UUID subject, puzzle, id;
    final JsonNode definition;
    final Set<UUID> usedActions = new HashSet<>();
    DraftRules.Draft draft;
    PendingReceipt pendingReceipt;
    long generation, revision, draftRevision, lastClientSequence = -1;
    final long boardVersion;

    Turn(
        UUID subject,
        UUID puzzle,
        UUID id,
        JsonNode definition,
        DraftRules.Draft draft,
        long boardVersion) {
      this.subject = subject;
      this.puzzle = puzzle;
      this.id = id;
      this.definition = definition;
      this.draft = draft;
      this.boardVersion = boardVersion;
    }
  }

  GameCoordinator(
      GameBoards boards,
      DraftRules rules,
      TurnLeaseRegistry leases,
      GameIdentityLedger identities,
      ConfirmationJournal journal,
      JdbcTemplate jdbc,
      Clock clock,
      GameWriterGuard writer) {
    this.boards = boards;
    this.rules = rules;
    this.leases = leases;
    this.identities = identities;
    this.journal = journal;
    this.jdbc = jdbc;
    this.clock = clock;
    this.writer = writer;
    writer.attach(this);
    this.currentDay = clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
  }

  @org.springframework.beans.factory.annotation.Autowired
  void setShapeService(GameShapeService shapes) {
    this.shapes = shapes;
  }

  synchronized JsonNode changeShape(UUID subject, JsonNode payload) {
    drainLeaseClears();
    UUID puzzle = BoardValidator.id(payload, "puzzleId");
    JsonNode cached = shapes.lookup(subject, payload);
    if (cached != null) return cached;
    if (turns.values().stream().anyMatch(turn -> turn.puzzle.equals(puzzle)))
      throw new HttpFailure(
          "PIECE_BUSY", "Finish or cancel all active drafts before changing shape");
    JsonNode result = shapes.apply(subject, payload);
    if ("updated".equals(result.path("outcome").asText())) forceResyncAll("gap");
    return result;
  }

  synchronized Started begin(UUID subject, UUID puzzle) {
    drainLeaseClears();
    JsonNode current = current(subject, puzzle);
    Instant closes = Instant.parse(current.path("endsAt").textValue());
    TurnLeaseRegistry.Info info =
        leases.open(puzzle, subject, closes, () -> identities.reserveTurn(subject, puzzle));
    Turn turn = turns.get(info.turn());
    if (turn == null) {
      JsonNode definition = current.path("definition");
      JsonNode snapshot = current.path("snapshot");
      turn =
          new Turn(
              subject,
              puzzle,
              info.turn(),
              definition.deepCopy(),
              rules.begin(
                  definition, snapshot, current.path("allowance").path("remaining").intValue()),
              snapshot.path("boardVersion").longValue());
      turns.put(turn.id, turn);
      invalidateIdentity(subject, puzzle, null);
    }
    return started(turn, info);
  }

  synchronized JsonNode begin(UUID subject, JsonNode payload) {
    drainLeaseClears();
    UUID puzzle = BoardValidator.id(payload, "puzzleId"),
        request = BoardValidator.id(payload, "requestId");
    GameIdentityLedger.Receipt existing = identities.lookup(subject, request, "begin", payload);
    if (existing != null) {
      if (existing.conflict())
        throw error("COMMAND_CONFLICT", "Request ID was already used with different content");
      return existing.result();
    }
    Started started = begin(subject, puzzle);
    ObjectNode result =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle.toString())
            .put("turnId", started.turnId().toString())
            .put("turnRevision", started.turnRevision())
            .put("generation", started.generation())
            .put("expiresAt", started.expiresAt().toString())
            .put("boardVersion", started.boardVersion());
    GameIdentityLedger.Receipt saved =
        identities.record(subject, puzzle, request, "begin", payload, result);
    if (saved.conflict())
      throw error("COMMAND_CONFLICT", "Request ID was already used with different content");
    return saved.result();
  }

  synchronized Changed preview(UUID subject, JsonNode payload) {
    return preview(subject, payload, null);
  }

  synchronized JsonNode draftSummary(UUID subject, UUID turnId) {
    drainLeaseClears();
    Turn turn = turns.get(turnId);
    if (turn == null || !turn.subject.equals(subject)) throw error("TURN_CLOSED", "Turn is closed");
    leases.info(subject, turn.id, turn.generation);
    ObjectNode summary =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", turn.puzzle.toString())
            .put("turnId", turn.id.toString())
            .put("generation", turn.generation)
            .put("draftRevision", turn.draftRevision)
            .put("pendingMoves", rules.pendingCount(turn.draft));
    ArrayNode charged = summary.putArray("chargedActionIds");
    ArrayNode movementCosts = summary.putArray("movementCosts");
    for (DraftRules.ActionView action : rules.actions(turn.draft)) {
      if (action.charged()) charged.add(action.actionId());
      movementCosts
          .addObject()
          .put("actionId", action.actionId())
          .put("additionalMoves", rules.additionalMoves(turn.draft, action.actionId()));
    }
    return summary;
  }

  synchronized Changed preview(UUID subject, JsonNode payload, UUID streamId) {
    drainLeaseClears();
    UUID turnId = BoardValidator.id(payload, "turnId"),
        puzzle = BoardValidator.id(payload, "puzzleId");
    long generation = payload.path("generation").longValue();
    Turn turn = turn(subject, turnId, generation);
    if (!turn.puzzle.equals(puzzle)) throw error("TURN_CLOSED", "Puzzle does not match the turn");
    Changed changed =
        preview(
            subject,
            turnId,
            generation,
            payload.path("sequence").longValue(),
            readAction(payload.path("action")),
            streamId);
    Subscriber sender = subscribers.get(streamId);
    if (sender != null && sender.v2() && sender.subject().equals(subject)) {
      ObjectNode receipt =
          mapper
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("type", "preview.accepted")
              .put("puzzleId", puzzle.toString())
              .put("turnId", turnId.toString())
              .put("generation", generation)
              .put("sequence", payload.path("sequence").longValue())
              .put("actionId", payload.path("action").path("actionId").asText())
              .put("draftRevision", turn.draftRevision);
      ArrayNode actions = receipt.putArray("actions");
      for (var frame : changed.previews())
        actions.add(previewJson(frame).path("action").deepCopy());
      if (!sender.sink().offer(receipt)) {
        sender.sink().slow();
        subscribers.remove(streamId);
      }
    }
    return changed;
  }

  synchronized TurnLeaseRegistry.Info heartbeat(UUID subject, JsonNode payload) {
    drainLeaseClears();
    UUID turnId = BoardValidator.id(payload, "turnId"),
        puzzle = BoardValidator.id(payload, "puzzleId");
    long generation = payload.path("generation").longValue();
    Turn turn = turn(subject, turnId, generation);
    if (!turn.puzzle.equals(puzzle)) throw error("TURN_CLOSED", "Puzzle does not match the turn");
    return heartbeat(subject, turnId, generation);
  }

  /**
   * Client action membership/revisions are requests; all actual transforms and charges are
   * recomputed.
   */
  synchronized Changed preview(
      UUID subject,
      UUID turnId,
      long generation,
      long clientSequence,
      PreviewGeneration.Action request) {
    return preview(subject, turnId, generation, clientSequence, request, null);
  }

  private Changed preview(
      UUID subject,
      UUID turnId,
      long generation,
      long clientSequence,
      PreviewGeneration.Action request,
      UUID streamId) {
    drainLeaseClears();
    Turn turn = turn(subject, turnId, generation);
    if (turn.draftRevision == 9_007_199_254_740_991L)
      throw error("INVALID_ACTION", "Draft revision exhausted");
    if (clientSequence < 0
        || clientSequence > 9_007_199_254_740_991L
        || clientSequence <= turn.lastClientSequence)
      throw error("INVALID_ACTION", "Stale preview sequence");
    Set<UUID> present = new HashSet<>();
    for (DraftRules.ActionView action : rules.actions(turn.draft))
      present.add(UUID.fromString(action.actionId()));
    if (!present.contains(request.actionId()) && turn.usedActions.contains(request.actionId()))
      throw error("INVALID_ACTION", "Action ID was already used");
    String moving = rules.resolveGroup(turn.draft, request.targetId().toString());
    Set<UUID> requestedPieces = new HashSet<>(request.affectedPieceIds());
    if (requestedPieces.size() != request.affectedPieceIds().size() || requestedPieces.isEmpty())
      throw error("INVALID_ACTION", "Invalid membership");
    List<DraftRules.GroupView> selected = new ArrayList<>();
    Set<UUID> actualPieces = new HashSet<>();
    for (DraftRules.GroupView group : rules.groups(turn.draft)) {
      Set<UUID> members = memberIds(group.group());
      boolean intersects = members.stream().anyMatch(requestedPieces::contains);
      if (intersects) {
        if (!requestedPieces.containsAll(members))
          throw error("INVALID_ACTION", "Partial group membership");
        selected.add(group);
        actualPieces.addAll(members);
      }
    }
    if (!actualPieces.equals(requestedPieces)
        || selected.stream()
            .noneMatch(g -> g.group().path("groupId").asText().equalsIgnoreCase(moving)))
      throw error("INVALID_ACTION", "Invalid membership");
    if (!connectedSelection(selected, turn.definition))
      throw error("INVALID_ACTION", "Cannot join disconnected groups");
    Map<UUID, Long> expectedRevisions = new HashMap<>();
    for (DraftRules.GroupView group : selected)
      for (String source : group.sources()) {
        JsonNode base = rules.baseGroup(turn.draft, source);
        expectedRevisions.put(UUID.fromString(source), base.path("revision").longValue());
      }
    Map<UUID, Long> suppliedRevisions = new HashMap<>();
    for (PreviewGeneration.BaseRevision base : request.baseRevisions())
      if (suppliedRevisions.put(base.groupId(), base.revision()) != null)
        throw error("INVALID_ACTION", "Duplicate revision");
    if (!expectedRevisions.equals(suppliedRevisions))
      throw error("STALE_REVISION", "Invalid source revisions");
    TurnLeaseRegistry.Info before = leases.info(subject, turnId, generation);
    Set<UUID> sources = Set.copyOf(expectedRevisions.keySet());
    leases.acquire(subject, turnId, generation, sources);
    Set<UUID> added = new HashSet<>(sources);
    added.removeAll(before.groups());
    try {
      ObjectNode transform = mapper.createObjectNode();
      transform
          .putObject("translation")
          .put("x", request.intendedTransform().translation().x())
          .put("y", request.intendedTransform().translation().y());
      transform.put("rotation", request.intendedTransform().rotation());
      DraftRules.Draft candidate =
          rules.move(turn.draft, moving, request.actionId().toString(), transform);
      JsonNode current = current(subject, turn.puzzle);
      if (hasForbiddenOverlap(turn, candidate, current))
        throw error("INVALID_ACTION", "Forbidden piece overlap");
      List<String> targets =
          new ArrayList<>(
              selected.stream()
                  .map(g -> g.group().path("groupId").textValue())
                  .filter(id -> !id.equalsIgnoreCase(moving))
                  .sorted(String.CASE_INSENSITIVE_ORDER)
                  .toList());
      List<String> order =
          targets.isEmpty()
              ? List.of()
              : attachmentOrder(
                  candidate,
                  moving,
                  targets,
                  turn,
                  current,
                  new HashSet<>(),
                  LocalGeometry.snapToleranceUpperBound(turn.definition, actualPieces));
      if (order == null) throw error("INVALID_ACTION", "Cannot join selected groups");
      String currentGroup = moving;
      for (String target : order) {
        String merged = identities.reserveGroup(turn.puzzle).toString();
        candidate = rules.attach(candidate, currentGroup, target, merged);
        currentGroup = merged;
      }
      if (hasForbiddenOverlap(turn, candidate, current))
        throw error("INVALID_ACTION", "Forbidden piece overlap");
      List<PreviewGeneration.Action> frames = serverActions(candidate);
      List<PreviewGeneration.Preview> published =
          leases.publishBatch(subject, turnId, generation, frames);
      turn.draft = candidate;
      turn.usedActions.add(request.actionId());
      turn.lastClientSequence = clientSequence;
      turn.draftRevision++;
      invalidateIdentity(subject, turn.puzzle, streamId);
      for (var frame : published) emit(previewJson(frame));
      return new Changed(published, rules.pendingCount(candidate));
    } catch (RuntimeException ex) {
      if (!added.isEmpty()) {
        try {
          leases.releaseUnused(subject, turnId, generation, added);
        } catch (TurnLeaseRegistry.LeaseError ignored) {
          /* Expiry already releases the reservations. */
        }
      }
      drainLeaseClears();
      throw ex;
    }
  }

  private List<String> attachmentOrder(
      DraftRules.Draft draft,
      String moving,
      List<String> remaining,
      Turn turn,
      JsonNode current,
      Set<DraftRules.JoinState> visited,
      double maxTolerance) {
    DraftRules.JoinState state = rules.joinState(draft, moving);
    if (!visited.add(state)) return null;
    if (visited.size() > MAX_JOIN_STATES)
      throw error("INTERNAL_ERROR", "Join search limit reached; retry with fewer groups");
    if (remaining.isEmpty()) {
      try {
        if (hasForbiddenOverlap(turn, draft, current)) return null;
        return new ArrayList<>();
      } catch (DraftRules.RuleError invalid) {
        return null;
      }
    }
    if (!remainingTransformsConnected(draft, remaining, maxTolerance)) return null;
    for (String target : remaining) {
      if (!rules.canAttach(draft, moving, target)) continue;
      String merged = UUID.randomUUID().toString();
      DraftRules.Draft next;
      try {
        next = rules.attach(draft, moving, target, merged);
      } catch (DraftRules.RuleError invalid) {
        continue;
      }
      List<String> rest = new ArrayList<>(remaining);
      rest.remove(target);
      List<String> order =
          attachmentOrder(next, merged, rest, turn, current, visited, maxTolerance);
      if (order != null) {
        order.addFirst(target);
        return order;
      }
    }
    return null;
  }

  private boolean hasForbiddenOverlap(Turn turn, DraftRules.Draft draft, JsonNode current) {
    JsonNode snapshot = current.path("snapshot");
    JsonNode canonical =
        rules
            .confirm(draft, snapshot, current.path("allowance").path("remaining").intValue())
            .snapshot();
    return LocalGeometry.hasForbiddenOverlap(turn.definition, canonical)
        || LocalGeometry.hasForbiddenOverlap(turn.definition, combined(snapshot, turn, draft));
  }

  private boolean remainingTransformsConnected(
      DraftRules.Draft draft, List<String> remaining, double maxTolerance) {
    List<JsonNode> transforms =
        remaining.stream().map(id -> rules.groupTransform(draft, id)).toList();
    Set<Integer> reached = new HashSet<>();
    List<Integer> pending = new ArrayList<>(List.of(0));
    reached.add(0);
    for (int i = 0; i < pending.size(); i++) {
      JsonNode from = transforms.get(pending.get(i));
      for (int j = 0; j < transforms.size(); j++) {
        if (reached.contains(j)) continue;
        JsonNode to = transforms.get(j);
        if (from.path("rotation").intValue() != to.path("rotation").intValue()) continue;
        double dx =
            (double) from.path("translation").path("x").longValue()
                - to.path("translation").path("x").longValue();
        double dy =
            (double) from.path("translation").path("y").longValue()
                - to.path("translation").path("y").longValue();
        if (Math.hypot(dx, dy) <= maxTolerance && reached.add(j)) pending.add(j);
      }
    }
    return reached.size() == remaining.size();
  }

  private boolean connectedSelection(List<DraftRules.GroupView> selected, JsonNode definition) {
    if (selected.size() <= 1) return true;
    Map<UUID, Integer> owners = new HashMap<>();
    for (int i = 0; i < selected.size(); i++)
      for (UUID piece : memberIds(selected.get(i).group())) owners.put(piece, i);
    List<Set<Integer>> neighbors = new ArrayList<>();
    for (int i = 0; i < selected.size(); i++) neighbors.add(new HashSet<>());
    for (JsonNode piece : definition.path("pieces")) {
      Integer owner = owners.get(BoardValidator.id(piece, "pieceId"));
      if (owner == null) continue;
      for (JsonNode edge : piece.path("edges")) {
        if (!edge.path("neighborPieceId").isTextual()) continue;
        Integer neighbor = owners.get(UUID.fromString(edge.path("neighborPieceId").textValue()));
        if (neighbor != null && !owner.equals(neighbor)) {
          neighbors.get(owner).add(neighbor);
          neighbors.get(neighbor).add(owner);
        }
      }
    }
    Set<Integer> reached = new HashSet<>();
    List<Integer> pending = new ArrayList<>(List.of(0));
    reached.add(0);
    for (int i = 0; i < pending.size(); i++)
      for (Integer neighbor : neighbors.get(pending.get(i)))
        if (reached.add(neighbor)) pending.add(neighbor);
    return reached.size() == selected.size();
  }

  synchronized void subscribe(UUID streamId, UUID subject, UUID puzzle, Long cursor, Sink sink) {
    subscribe(streamId, subject, puzzle, cursor, sink, false);
  }

  synchronized void subscribe(
      UUID streamId, UUID subject, UUID puzzle, Long cursor, Sink sink, boolean v2) {
    drainLeaseClears();
    JsonNode current = v2 ? mapper.valueToTree(current(subject)) : current(subject, puzzle);
    if (!puzzle.toString().equalsIgnoreCase(current.path("snapshot").path("puzzleId").asText()))
      throw error("PUZZLE_UNAVAILABLE", "Puzzle is unavailable");
    JsonNode snapshot = current.path("snapshot");
    String reason = null;
    if (cursor != null && !seenSubjects.contains(subject)) reason = "restart";
    else if (cursor != null && cursor != snapshot.path("boardVersion").longValue())
      reason = "history_expired";
    if (reason != null) {
      ObjectNode message =
          mapper
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("type", "resyncRequired")
              .put("puzzleId", puzzle.toString())
              .put("reason", reason);
      subscribers.remove(streamId);
      if (!sink.offer(message)) sink.slow();
      if (seenSubjects.size() < 10000) seenSubjects.add(subject);
      return;
    }
    ObjectNode reply =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", v2 ? "subscribed.v2" : "subscribed");
    reply.set("snapshot", snapshot.deepCopy());
    reply.put("watermark", snapshot.path("boardVersion").longValue());
    ArrayNode previews = reply.putArray("previews");
    for (var preview : leases.previews(puzzle)) previews.add(previewJson(preview));
    if (v2) {
      reply.set("allowance", current.path("allowance").deepCopy());
      reply.set("ownTurn", current.path("ownTurn").deepCopy());
      ArrayNode actions = reply.putArray("actions");
      long nextSequence = 0;
      reply.putNull("draftRevision");
      if (!current.path("ownTurn").isNull()) {
        Turn turn = turns.get(BoardValidator.id(current.path("ownTurn"), "turnId"));
        if (turn == null) throw error("TURN_CLOSED", "Turn is closed");
        nextSequence = Math.min(turn.lastClientSequence + 1, 9_007_199_254_740_991L);
        reply.put("draftRevision", turn.draftRevision);
        for (var preview : leases.previews(puzzle))
          if (preview.turnId().equals(turn.id))
            actions.add(previewJson(preview).path("action").deepCopy());
      }
      reply.put("nextSequence", nextSequence);
    }
    if (!sink.offer(reply)) {
      subscribers.remove(streamId);
      sink.slow();
      return;
    }
    subscribers.put(streamId, new Subscriber(subject, puzzle, sink, v2));
    if (seenSubjects.size() < 10000) seenSubjects.add(subject);
  }

  synchronized void unsubscribe(UUID streamId) {
    subscribers.remove(streamId);
  }

  synchronized Map<String, Object> current(UUID subject) {
    drainLeaseClears();
    Map<String, Object> current = boards.currentFor(subject);
    if (subject == null) return current;
    String puzzle = ((Map<?, ?>) current.get("snapshot")).get("puzzleId").toString();
    for (Turn turn : turns.values())
      if (turn.subject.equals(subject) && turn.puzzle.toString().equalsIgnoreCase(puzzle)) {
        try {
          TurnLeaseRegistry.Info info = leases.lookupInfo(subject, turn.id);
          current.put("ownTurn", mapper.convertValue(startedJson(started(turn, info)), Map.class));
          break;
        } catch (TurnLeaseRegistry.LeaseError ignored) {
          drainLeaseClears();
          break;
        }
      }
    return current;
  }

  private JsonNode startedJson(Started value) {
    return mapper
        .createObjectNode()
        .put("schemaVersion", 1)
        .put("puzzleId", value.puzzle().toString())
        .put("turnId", value.turnId().toString())
        .put("turnRevision", value.turnRevision())
        .put("generation", value.generation())
        .put("expiresAt", value.expiresAt().toString())
        .put("boardVersion", value.boardVersion());
  }

  /** Last socket for the subject closed; extend its active draft's disconnect grace. */
  synchronized void disconnected(UUID subject) {
    drainLeaseClears();
    for (Turn turn : List.copyOf(turns.values()))
      if (turn.subject.equals(subject)) {
        try {
          heartbeat(subject, turn.id, turn.generation);
        } catch (TurnLeaseRegistry.LeaseError ignored) {
          drainLeaseClears();
        }
      }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
  public synchronized void tick() {
    if (!writer.healthy()) {
      forceResyncAll("restart");
      if (!writer.recover()) return;
    }
    drainLeaseClears();
    recoverConfirmation();
    java.time.LocalDate day = clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
    if (day.equals(currentDay)) return;
    ObjectNode changed =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "puzzleChanged")
            .put("day", day.toString());
    try {
      JsonNode current = mapper.valueToTree(boards.current());
      changed
          .put("puzzleId", current.path("snapshot").path("puzzleId").asText())
          .put("available", true);
    } catch (PuzzleUnavailable ex) {
      changed.putNull("puzzleId");
      changed.put("available", false);
    }
    emit(changed);
    subscribers.clear();
    currentDay = day;
  }

  private void recoverConfirmation() {
    List<Turn> pending = new ArrayList<>();
    for (Turn turn : List.copyOf(turns.values())) {
      try {
        if (leases.confirming(turn.subject, turn.id, turn.generation) != null) pending.add(turn);
      } catch (TurnLeaseRegistry.LeaseError expired) {
        drainLeaseClears();
      }
    }
    if (pending.isEmpty()) return;
    pending.sort(java.util.Comparator.comparing(t -> t.id));
    Turn turn = pending.getFirst();
    if (recoveryCursor != null)
      for (Turn candidate : pending)
        if (candidate.id.compareTo(recoveryCursor) > 0) {
          turn = candidate;
          break;
        }
    recoveryCursor = turn.id;
    UUID command = leases.confirming(turn.subject, turn.id, turn.generation);
    JsonNode result;
    try {
      result = journal.findForRecovery(turn.subject, command);
    } catch (RuntimeException unavailable) {
      return;
    }
    reconcileConfirmation(turn, command, result);
  }

  synchronized Subscription subscribe(UUID puzzle) {
    drainLeaseClears();
    JsonNode current = current(null, puzzle);
    return new Subscription(current.path("snapshot").deepCopy(), leases.previews(puzzle));
  }

  synchronized TurnLeaseRegistry.Info heartbeat(UUID subject, UUID turnId, long generation) {
    drainLeaseClears();
    turn(subject, turnId, generation);
    TurnLeaseRegistry.Info info = leases.heartbeat(subject, turnId, generation);
    Turn turn = turns.get(turnId);
    if (!rules.actions(turn.draft).isEmpty())
      for (var p : leases.publishBatch(subject, turnId, generation, serverActions(turn.draft)))
        emit(previewJson(p));
    return info;
  }

  /** Returns a durable result only after the transaction has completed. */
  synchronized JsonNode confirm(UUID subject, JsonNode payload) {
    drainLeaseClears();
    UUID puzzle = BoardValidator.id(payload, "puzzleId"),
        turnId = BoardValidator.id(payload, "turnId"),
        command = BoardValidator.id(payload, "commandId");
    JsonNode cached = journal.lookup(subject, puzzle, command, payload);
    if (cached != null) {
      if ("COMMAND_CONFLICT".equals(cached.path("error").path("code").asText())) return cached;
      Turn pending = turns.get(turnId);
      if (pending != null
          && pending.subject.equals(subject)
          && pending.puzzle.equals(puzzle)
          && pending.generation == payload.path("generation").longValue()) {
        try {
          UUID processing = leases.confirming(subject, turnId, pending.generation);
          if (command.equals(processing)) {
            if ("confirmed".equals(cached.path("outcome").asText())) forceResyncAll("gap");
            finishConfirmedTurn(pending, command, cached, -1);
          }
        } catch (TurnLeaseRegistry.LeaseError ignored) {
          drainLeaseClears();
        }
      }
      return cached;
    }
    drainLeaseClears();
    long generation = payload.path("generation").longValue();
    Turn pending = turns.get(turnId);
    if (pending != null
        && pending.subject.equals(subject)
        && pending.puzzle.equals(puzzle)
        && pending.generation == generation
        && command.equals(leases.confirming(subject, turnId, generation)))
      unpinConfirm(pending, command);
    if (!turns.containsKey(turnId) && identities.ownsTurn(subject, puzzle, turnId)) {
      // Resolve expired/restarted turns against the original puzzle under its board lock.
      return journal.confirm(
          subject,
          puzzle,
          command,
          payload,
          (definition, latest, remaining) -> {
            throw new ConfirmationJournal.Rejected("TURN_CLOSED", "Turn is closed");
          });
    }
    Turn turn = turn(subject, turnId, generation);
    if (!turn.puzzle.equals(puzzle)
        || payload.path("turnRevision").longValue() != turn.revision
        || (payload.has("draftRevision")
            && payload.path("draftRevision").longValue() != turn.draftRevision))
      throw error("TURN_CLOSED", "Turn revision changed");
    rules.requireSupported(turn.draft);
    Map<UUID, PreviewGeneration.Action> expected = new HashMap<>();
    for (PreviewGeneration.Action action : serverActions(turn.draft))
      expected.put(action.actionId(), action);
    if (payload.path("actions").size() != expected.size() || expected.isEmpty())
      throw error("INVALID_ACTION", "Confirm does not match the draft");
    Set<UUID> seen = new HashSet<>();
    for (JsonNode item : payload.path("actions")) {
      PreviewGeneration.Action submitted = readAction(item);
      if (!seen.add(submitted.actionId())
          || !equivalent(expected.get(submitted.actionId()), submitted))
        throw error("INVALID_ACTION", "Confirm does not match the draft");
    }
    UUID processing = leases.confirming(subject, turnId, generation);
    if (processing != null && !command.equals(processing))
      throw error("TURN_CLOSED", "Confirm is processing");
    boolean[] pinned = {processing != null};
    long[] changedVersion = {-1};
    JsonNode result;
    try {
      result =
          journal.confirm(
              subject,
              puzzle,
              command,
              payload,
              (definition, latest, remaining) -> {
                try {
                  DraftRules.Confirmation proposal = rules.confirm(turn.draft, latest, remaining);
                  if (LocalGeometry.hasForbiddenOverlap(
                      definition, combined(latest, turn, turn.draft)))
                    throw new ConfirmationJournal.Rejected(
                        "INVALID_ACTION", "Forbidden piece overlap");
                  if (proposal.snapshot().path("boardVersion").longValue()
                      > latest.path("boardVersion").longValue())
                    changedVersion[0] = proposal.snapshot().path("boardVersion").longValue();
                  return new ConfirmationJournal.Plan(proposal.snapshot(), proposal.charged());
                } catch (DraftRules.RuleError ex) {
                  String code =
                      "UNSUPPORTED_LEGACY_ORIENTATION".equals(ex.code) ? "INVALID_ACTION" : ex.code;
                  throw new ConfirmationJournal.Rejected(code, ex.getMessage());
                }
              },
              admittedAt -> {
                leases.pinConfirm(subject, turnId, generation, command, admittedAt);
                pinned[0] = true;
                for (var preview : leases.previews(puzzle))
                  if (preview.turnId().equals(turnId)) emit(previewJson(preview));
              });
    } catch (RuntimeException failure) {
      if (pinned[0]) {
        // A commit error may have an unknown outcome. Only clear after a fresh durable lookup.
        JsonNode resolved;
        try {
          resolved = journal.lookup(subject, puzzle, command, payload);
        } catch (RuntimeException unavailable) {
          throw error("INTERNAL_ERROR", "Confirmation outcome is unknown; retry the same command");
        }
        if (resolved != null) {
          finishConfirmedTurn(turn, command, resolved, changedVersion[0]);
          return resolved;
        }
        unpinConfirm(turn, command);
      }
      throw failure;
    }
    if (pinned[0]) finishConfirmedTurn(turn, command, result, changedVersion[0]);
    else {
      try {
        leases.clear(subject, turnId, generation, "rejected");
      } catch (TurnLeaseRegistry.LeaseError ignored) {
        /* Expiry or rollover already cleared it. */
      }
      drainLeaseClears();
    }
    return result;
  }

  synchronized JsonNode commandStatus(UUID subject, UUID command) {
    drainLeaseClears();
    JsonNode result = journal.find(subject, command);
    for (Turn turn : List.copyOf(turns.values())) {
      if (!turn.subject.equals(subject)) continue;
      UUID processing;
      try {
        processing = leases.confirming(subject, turn.id, turn.generation);
      } catch (TurnLeaseRegistry.LeaseError expired) {
        drainLeaseClears();
        continue;
      }
      if (!command.equals(processing)) continue;
      reconcileConfirmation(turn, command, result);
    }
    return result;
  }

  private void reconcileConfirmation(Turn turn, UUID command, JsonNode result) {
    if (result == null) unpinConfirm(turn, command);
    else {
      if ("confirmed".equals(result.path("outcome").asText())) forceResyncAll("gap");
      finishConfirmedTurn(turn, command, result, -1);
    }
  }

  private void unpinConfirm(Turn turn, UUID command) {
    for (var preview : leases.unpinConfirm(turn.subject, turn.id, turn.generation, command))
      emit(previewJson(preview));
    drainLeaseClears();
  }

  private void finishConfirmedTurn(Turn turn, UUID command, JsonNode result, long changedVersion) {
    boolean confirmed = "confirmed".equals(result.path("outcome").asText());
    if (confirmed && changedVersion >= 0) {
      try {
        List<String> rows =
            jdbc.query(
                "SELECT event::text FROM game_events WHERE puzzle_id = ? AND board_version = ?",
                (rs, row) -> rs.getString(1),
                turn.puzzle,
                changedVersion);
        if (rows.size() != 1) forceResyncAll("gap");
        else emit(mapper.readTree(rows.getFirst()));
      } catch (RuntimeException | java.io.IOException ex) {
        forceResyncAll("gap");
      }
    }
    leases.finishConfirm(turn.subject, turn.id, turn.generation, command, confirmed);
    drainLeaseClears();
  }

  private PreviewGeneration.Action readAction(JsonNode item) {
    List<UUID> pieces = new ArrayList<>();
    for (JsonNode p : item.path("affectedPieceIds")) pieces.add(UUID.fromString(p.textValue()));
    List<PreviewGeneration.BaseRevision> revisions = new ArrayList<>();
    for (JsonNode r : item.path("baseRevisions"))
      revisions.add(
          new PreviewGeneration.BaseRevision(
              UUID.fromString(r.path("groupId").textValue()), r.path("revision").longValue()));
    JsonNode transform = item.path("intendedTransform");
    return new PreviewGeneration.Action(
        BoardValidator.id(item, "actionId"),
        item.path("kind").textValue(),
        BoardValidator.id(item, "targetId"),
        pieces,
        revisions,
        new PreviewGeneration.Transform(
            new PreviewGeneration.Point(
                transform.path("translation").path("x").longValue(),
                transform.path("translation").path("y").longValue()),
            transform.path("rotation").intValue()));
  }

  private boolean equivalent(PreviewGeneration.Action a, PreviewGeneration.Action b) {
    return a != null
        && a.actionId().equals(b.actionId())
        && a.targetId().equals(b.targetId())
        && a.intendedTransform().equals(b.intendedTransform())
        && a.affectedPieceIds().size() == b.affectedPieceIds().size()
        && new HashSet<>(a.affectedPieceIds()).equals(new HashSet<>(b.affectedPieceIds()))
        && a.baseRevisions().size() == b.baseRevisions().size()
        && new HashSet<>(a.baseRevisions()).equals(new HashSet<>(b.baseRevisions()));
  }

  synchronized JsonNode cancel(UUID subject, JsonNode payload) {
    drainLeaseClears();
    UUID puzzle = BoardValidator.id(payload, "puzzleId"),
        turnId = BoardValidator.id(payload, "turnId"),
        requestId = BoardValidator.id(payload, "requestId");
    GameIdentityLedger.Receipt existing = identities.lookup(subject, requestId, "cancel", payload);
    if (existing != null) {
      if (existing.conflict())
        throw error("COMMAND_CONFLICT", "Request ID was already used with different content");
      Turn pending = turns.get(turnId);
      if (pending != null
          && pending.subject.equals(subject)
          && pending.puzzle.equals(puzzle)
          && pending.generation == payload.path("generation").longValue()
          && pending.revision == payload.path("turnRevision").longValue()) {
        try {
          String outcome = existing.result().path("outcome").asText();
          if ("canceled".equals(outcome)) {
            leases.clear(subject, turnId, pending.generation, "canceled");
          } else if ("confirmed".equals(outcome)) {
            forceResyncAll("gap");
            UUID processing = leases.confirming(subject, turnId, pending.generation);
            if (processing != null) finishConfirmedTurn(pending, processing, existing.result(), -1);
            else leases.clear(subject, turnId, pending.generation, "confirmed");
          }
        } catch (TurnLeaseRegistry.LeaseError ignored) {
          drainLeaseClears();
        }
        drainLeaseClears();
      }
      return existing.result();
    }
    drainLeaseClears();
    if (!identities.ownsTurn(subject, puzzle, turnId)) throw error("TURN_CLOSED", "Unknown turn");
    Turn turn = turns.get(turnId);
    JsonNode confirmed = journal.confirmedForTurn(subject, turnId);
    UUID processing = null;
    if (turn != null) {
      if (!turn.subject.equals(subject)
          || !turn.puzzle.equals(puzzle)
          || turn.generation != payload.path("generation").longValue()
          || turn.revision != payload.path("turnRevision").longValue())
        throw error("TURN_CLOSED", "Turn revision changed");
      processing = leases.confirming(subject, turnId, turn.generation);
    }
    String outcome =
        confirmed != null
            ? "confirmed"
            : processing != null ? "confirming" : turn != null ? "canceled" : "already_canceled";
    JsonNode boardState = confirmed != null ? confirmed : boardState(subject, puzzle);
    ObjectNode result =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle.toString())
            .put("turnId", turnId.toString())
            .put("requestId", requestId.toString())
            .put("outcome", outcome)
            .put("boardVersion", boardState.path("boardVersion").longValue());
    result.set("allowance", boardState.path("allowance").deepCopy());
    if (confirmed != null) result.put("commandId", confirmed.path("commandId").textValue());
    else if (processing != null) result.put("commandId", processing.toString());
    else result.putNull("commandId");
    if (processing != null && confirmed == null)
      return result; // Pending is not a permanent receipt.
    Instant acceptedAt = clock.instant();
    if (turn != null && processing == null) leases.info(subject, turnId, turn.generation);
    UUID pinned = processing;
    if (turn != null)
      turn.pendingReceipt =
          new PendingReceipt(
              requestId,
              "cancel",
              payload.deepCopy(),
              () -> applyCancel(turn, confirmed, pinned, acceptedAt));
    GameIdentityLedger.Receipt saved =
        identities.record(subject, puzzle, requestId, "cancel", payload, result);
    if (turn != null) turn.pendingReceipt = null;
    if (saved.conflict())
      throw error("COMMAND_CONFLICT", "Request ID was already used with different content");
    if (turn != null) applyCancel(turn, confirmed, processing, acceptedAt);
    return saved.result();
  }

  private void applyCancel(Turn turn, JsonNode confirmed, UUID processing, Instant acceptedAt) {
    if (confirmed == null) {
      leases.clearAt(turn.subject, turn.id, turn.generation, "canceled", acceptedAt);
      drainLeaseClears();
    } else {
      forceResyncAll("gap");
      if (processing != null) finishConfirmedTurn(turn, processing, confirmed, -1);
      else {
        leases.clearAt(turn.subject, turn.id, turn.generation, "confirmed", acceptedAt);
        drainLeaseClears();
      }
    }
  }

  synchronized JsonNode undo(UUID subject, JsonNode payload) {
    drainLeaseClears();
    JsonNode draftRevision = payload.path("draftRevision");
    if (!draftRevision.isIntegralNumber()
        || !draftRevision.canConvertToLong()
        || draftRevision.longValue() < 0
        || draftRevision.longValue() > 9_007_199_254_740_991L)
      throw new IllegalArgumentException("Invalid draft revision");
    UUID puzzle = BoardValidator.id(payload, "puzzleId"),
        turnId = BoardValidator.id(payload, "turnId"),
        requestId = BoardValidator.id(payload, "requestId"),
        actionId = BoardValidator.id(payload, "actionId");
    GameIdentityLedger.Receipt existing = identities.lookup(subject, requestId, "undo", payload);
    if (existing != null) {
      if (existing.conflict())
        throw error("COMMAND_CONFLICT", "Request ID was already used with different content");
      Turn pending = turns.get(turnId);
      if (pending != null
          && pending.subject.equals(subject)
          && pending.puzzle.equals(puzzle)
          && pending.generation == payload.path("generation").longValue()
          && pending.revision == payload.path("turnRevision").longValue()
          && pending.draftRevision == draftRevision.longValue()) {
        try {
          applyUndo(pending, actionId, clock.instant());
        } catch (TurnLeaseRegistry.LeaseError ignored) {
          drainLeaseClears();
        }
      }
      return existing.result();
    }
    drainLeaseClears();
    long generation = payload.path("generation").longValue();
    Turn turn = turn(subject, turnId, generation);
    if (!turn.puzzle.equals(puzzle) || turn.revision != payload.path("turnRevision").longValue())
      throw error("TURN_CLOSED", "Turn revision changed");
    if (turn.draftRevision != draftRevision.longValue())
      throw error("STALE_REVISION", "Draft revision changed");
    Instant acceptedAt = clock.instant();
    leases.info(subject, turnId, generation);
    DraftRules.Draft updated = rules.undo(turn.draft, actionId.toString());
    List<PreviewGeneration.Action> survivors = serverActions(updated);
    leases.validateUndoAt(subject, turnId, generation, survivors, acceptedAt);
    if (hasForbiddenOverlap(turn, updated, current(subject, puzzle)))
      throw error("INVALID_ACTION", "Undo would cause forbidden piece overlap");
    if (turn.revision == 9_007_199_254_740_991L
        || turn.draftRevision == 9_007_199_254_740_991L
        || generation == 9_007_199_254_740_991L)
      throw error("INVALID_ACTION", "Turn revision exhausted");
    ObjectNode result =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle.toString())
            .put("turnId", turnId.toString())
            .put("requestId", requestId.toString())
            .put("turnRevision", turn.revision + 1)
            .put("generation", generation + 1)
            .put("actionId", actionId.toString());
    ArrayNode remaining = result.putArray("actions");
    for (var action : survivors) remaining.add(mapper.valueToTree(action));
    turn.pendingReceipt =
        new PendingReceipt(
            requestId,
            "undo",
            payload.deepCopy(),
            () -> applyUndo(turn, updated, survivors, acceptedAt));
    GameIdentityLedger.Receipt saved =
        identities.record(subject, puzzle, requestId, "undo", payload, result);
    turn.pendingReceipt = null;
    if (saved.conflict())
      throw error("COMMAND_CONFLICT", "Request ID was already used with different content");
    applyUndo(turn, updated, survivors, acceptedAt);
    return saved.result();
  }

  private void applyUndo(Turn turn, UUID actionId, Instant acceptedAt) {
    DraftRules.Draft updated = rules.undo(turn.draft, actionId.toString());
    applyUndo(turn, updated, serverActions(updated), acceptedAt);
  }

  private void applyUndo(
      Turn turn,
      DraftRules.Draft updated,
      List<PreviewGeneration.Action> survivors,
      Instant acceptedAt) {
    TurnLeaseRegistry.Info info =
        leases.fenceUndoAt(turn.subject, turn.id, turn.generation, survivors, acceptedAt);
    turn.draft = updated;
    turn.generation = info.generation();
    turn.revision++;
    turn.draftRevision++;
    turn.lastClientSequence = -1;
    invalidateIdentity(turn.subject, turn.puzzle, null);
    drainLeaseClears();
    for (var preview : leases.previews(turn.puzzle))
      if (preview.turnId().equals(turn.id)) emit(previewJson(preview));
    drainLeaseClears();
  }

  private JsonNode boardState(UUID subject, UUID puzzle) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT b.board_version, COALESCE(a.used, 0) AS used FROM game_boards b LEFT JOIN game_allowances a ON a.puzzle_id = b.puzzle_id AND a.subject_id = ? WHERE b.puzzle_id = ?",
            subject,
            puzzle);
    if (rows.isEmpty()) throw error("PUZZLE_UNAVAILABLE", "Puzzle is unavailable");
    long version = ((Number) rows.getFirst().get("board_version")).longValue();
    int used = ((Number) rows.getFirst().get("used")).intValue();
    ObjectNode state = mapper.createObjectNode().put("boardVersion", version);
    state.putObject("allowance").put("limit", 5).put("used", used).put("remaining", 5 - used);
    return state;
  }

  synchronized Events drainEvents() {
    drainLeaseClears();
    Events result = new Events(List.copyOf(events), resyncRequired);
    events.clear();
    resyncRequired = false;
    return result;
  }

  private JsonNode combined(JsonNode snapshot, Turn changed, DraftRules.Draft replacement) {
    Map<String, JsonNode> groups = new LinkedHashMap<>();
    for (JsonNode g : snapshot.path("groups"))
      groups.put(g.path("groupId").asText().toLowerCase(java.util.Locale.ROOT), g.deepCopy());
    for (Turn turn : turns.values())
      if (turn.puzzle.equals(changed.puzzle)) {
        DraftRules.Draft draft = turn == changed ? replacement : turn.draft;
        for (DraftRules.GroupView group : rules.groups(draft))
          if (!group.actions().isEmpty()) {
            for (String source : group.sources())
              if (groups.remove(source) == null)
                throw error("STALE_REVISION", "Preview source changed");
            groups.put(
                group.group().path("groupId").asText().toLowerCase(java.util.Locale.ROOT),
                group.group().deepCopy());
          }
      }
    ObjectNode result = snapshot.deepCopy();
    ArrayNode values = result.putArray("groups");
    groups.values().forEach(values::add);
    return result;
  }

  private List<PreviewGeneration.Action> serverActions(DraftRules.Draft draft) {
    List<PreviewGeneration.Action> actions = new ArrayList<>();
    for (DraftRules.ActionView action : rules.actions(draft)) {
      JsonNode group = action.component().group();
      List<UUID> pieces = memberIds(group).stream().sorted().toList();
      List<PreviewGeneration.BaseRevision> revisions =
          action.component().sources().stream()
              .sorted()
              .map(id ->
                  new PreviewGeneration.BaseRevision(
                      UUID.fromString(id), rules.baseGroup(draft, id).path("revision").longValue()))
              .toList();
      JsonNode transform = group.path("transform");
      String kind = action.kind();
      UUID target =
          pieces.size() == 1
              ? pieces.getFirst()
              : UUID.fromString(group.path("groupId").textValue());
      actions.add(
          new PreviewGeneration.Action(
              UUID.fromString(action.actionId()),
              kind,
              target,
              pieces,
              revisions,
              new PreviewGeneration.Transform(
                  new PreviewGeneration.Point(
                      transform.path("translation").path("x").longValue(),
                      transform.path("translation").path("y").longValue()),
                  transform.path("rotation").intValue())));
    }
    return actions;
  }

  private Set<UUID> memberIds(JsonNode group) {
    Set<UUID> result = new HashSet<>();
    for (JsonNode p : group.path("pieceIds")) result.add(UUID.fromString(p.textValue()));
    return result;
  }

  private JsonNode current(UUID subject, UUID puzzle) {
    JsonNode current = mapper.valueToTree(boards.currentFor(subject));
    if (!puzzle.toString().equalsIgnoreCase(current.path("snapshot").path("puzzleId").asText()))
      throw error("PUZZLE_UNAVAILABLE", "Puzzle is unavailable");
    return current;
  }

  private Turn turn(UUID subject, UUID turnId, long generation) {
    Turn turn = turns.get(turnId);
    if (turn == null || !turn.subject.equals(subject) || turn.generation != generation)
      throw error("TURN_CLOSED", "Turn is closed or fenced");
    return turn;
  }

  private Started started(Turn turn, TurnLeaseRegistry.Info info) {
    return new Started(
        turn.puzzle, turn.id, turn.revision, turn.generation, info.expiresAt(), turn.boardVersion);
  }

  private void drainLeaseClears() {
    writer.check();
    for (Turn turn : List.copyOf(turns.values())) {
      PendingReceipt pending = turn.pendingReceipt;
      if (pending == null) continue;
      GameIdentityLedger.Receipt receipt =
          identities.lookup(turn.subject, pending.request(), pending.kind(), pending.payload());
      turn.pendingReceipt = null;
      if (receipt != null && !receipt.conflict()) pending.apply().run();
    }
    TurnLeaseRegistry.ClearBatch batch = leases.drainClears();
    if (batch.resyncRequired()) {
      forceResyncAll("gap");
      turns.keySet().retainAll(leases.activeTurns());
    }
    for (var clear : batch.events()) {
      Turn cleared = turns.get(clear.turn());
      if (cleared != null) invalidateIdentity(cleared.subject, cleared.puzzle, null);
      if (!"undone".equals(clear.reason())) turns.remove(clear.turn());
      ObjectNode message =
          mapper
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("type", "previewCleared")
              .put("puzzleId", clear.puzzle().toString())
              .put("turnId", clear.turn().toString())
              .put("generation", clear.generation())
              .put("reason", clear.reason());
      emit(message);
    }
  }

  private void invalidateIdentity(UUID subject, UUID puzzle, UUID exceptStream) {
    var iterator = subscribers.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      Subscriber subscriber = entry.getValue();
      if (subscriber.v2()
          && subscriber.subject().equals(subject)
          && subscriber.puzzle().equals(puzzle)
          && !entry.getKey().equals(exceptStream)) {
        ObjectNode event =
            mapper
                .createObjectNode()
                .put("schemaVersion", 1)
                .put("type", "resyncRequired")
                .put("puzzleId", puzzle.toString())
                .put("reason", "gap");
        if (!subscriber.sink().offer(event)) subscriber.sink().slow();
        iterator.remove();
      }
    }
  }

  private void forceResyncAll(String reason) {
    resyncRequired = true;
    for (Subscriber subscriber : subscribers.values()) {
      ObjectNode message =
          mapper
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("type", "resyncRequired")
              .put("puzzleId", subscriber.puzzle().toString())
              .put("reason", reason);
      if (!subscriber.sink().offer(message)) subscriber.sink().slow();
    }
    subscribers.clear();
  }

  private void emit(JsonNode event) {
    if (events.size() < 1024) events.add(event);
    else resyncRequired = true;
    boolean lifecycle = "puzzleChanged".equals(event.path("type").asText());
    UUID eventPuzzle =
        event.path("puzzleId").isTextual()
            ? UUID.fromString(event.path("puzzleId").textValue())
            : null;
    var iterator = subscribers.entrySet().iterator();
    while (iterator.hasNext()) {
      Subscriber subscriber = iterator.next().getValue();
      if (lifecycle || subscriber.puzzle().equals(eventPuzzle)) {
        if (!subscriber.sink().offer(event)) {
          subscriber.sink().slow();
          iterator.remove();
        }
      }
    }
  }

  private JsonNode previewJson(PreviewGeneration.Preview p) {
    ObjectNode out =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("type", "preview")
            .put("puzzleId", p.puzzleId().toString())
            .put("turnId", p.turnId().toString())
            .put("generation", p.generation())
            .put("sequence", p.sequence());
    ObjectNode a =
        out.putObject("action")
            .put("actionId", p.action().actionId().toString())
            .put("kind", p.action().kind())
            .put("targetId", p.action().targetId().toString());
    ArrayNode pieces = a.putArray("affectedPieceIds");
    p.action().affectedPieceIds().forEach(id -> pieces.add(id.toString()));
    ArrayNode revisions = a.putArray("baseRevisions");
    p.action()
        .baseRevisions()
        .forEach(r ->
            revisions
                .addObject()
                .put("groupId", r.groupId().toString())
                .put("revision", r.revision()));
    ObjectNode transform = a.putObject("intendedTransform");
    transform
        .putObject("translation")
        .put("x", p.action().intendedTransform().translation().x())
        .put("y", p.action().intendedTransform().translation().y());
    transform.put("rotation", p.action().intendedTransform().rotation());
    out.put("expiresAt", p.expiresAt().toString());
    return out;
  }

  private static DraftRules.RuleError error(String code, String message) {
    return new DraftRules.RuleError(code, message);
  }
}
