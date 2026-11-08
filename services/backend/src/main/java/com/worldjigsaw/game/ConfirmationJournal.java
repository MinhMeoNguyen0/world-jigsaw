package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal transaction boundary for a future trusted rules coordinator. This is not an endpoint: no
 * client may supply the planner, its charge, or its proposed snapshot. The planner must recheck
 * actions, leases, history, revisions and quota against the supplied current state.
 */
@Service
class ConfirmationJournal {
  private static final long MAX_SAFE = 9_007_199_254_740_991L;
  private final JdbcTemplate jdbc;
  private final BoardValidator validator;
  private final Clock clock;
  private final ObjectMapper mapper = new ObjectMapper();

  interface Planner {
    Plan apply(JsonNode definition, JsonNode latest, int remaining);
  }

  record Plan(JsonNode snapshot, int charged) {}

  static class Rejected extends RuntimeException {
    final String code;

    Rejected(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  ConfirmationJournal(JdbcTemplate jdbc, BoardValidator validator, Clock clock) {
    this.jdbc = jdbc;
    this.validator = validator;
    this.clock = clock;
  }

  /** Subject comes only from a trusted session. The board lock is held until transaction end. */
  @Transactional(timeout = 30)
  public JsonNode confirm(
      UUID subject, UUID puzzle, UUID command, JsonNode payload, Planner planner) {
    return confirm(subject, puzzle, command, payload, planner, ignored -> {});
  }

  /** Admission callback is internal and runs after locking and sampling the close time. */
  @Transactional(timeout = 30)
  public JsonNode confirm(
      UUID subject,
      UUID puzzle,
      UUID command,
      JsonNode payload,
      Planner planner,
      java.util.function.Consumer<Instant> admitted) {
    if (!puzzle.equals(BoardValidator.id(payload, "puzzleId"))
        || !command.equals(BoardValidator.id(payload, "commandId"))) {
      throw new IllegalArgumentException("command identity mismatch");
    }
    String canonical;
    try {
      canonical = new JsonCanonicalizer(payload.toString()).getEncodedString();
    } catch (IOException ex) {
      throw new IllegalArgumentException("invalid payload", ex);
    }

    jdbc.update("INSERT INTO game_subjects(subject_id) VALUES (?) ON CONFLICT DO NOTHING", subject);
    jdbc.queryForObject(
        "SELECT subject_id FROM game_subjects WHERE subject_id = ? FOR UPDATE",
        UUID.class,
        subject);
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT canonical_payload, result::text AS result FROM game_commands WHERE subject_id = ? AND command_id = ?",
            subject,
            command);
    if (!existing.isEmpty()) {
      if (!canonical.equals(existing.getFirst().get("canonical_payload"))) {
        return rejection(
            puzzle,
            command,
            "COMMAND_CONFLICT",
            "Command ID was already used with different content");
      }
      return json((String) existing.getFirst().get("result"));
    }
    var rows =
        jdbc.queryForList(
            "SELECT definition::text AS definition, definition_version, board_version, closes_at FROM game_boards WHERE puzzle_id = ? FOR UPDATE",
            puzzle);
    if (rows.isEmpty()) throw new IllegalArgumentException("unknown puzzle");
    Map<String, Object> row = rows.getFirst();
    Instant closesAt = ((java.sql.Timestamp) row.get("closes_at")).toInstant();
    // Admission is sampled after obtaining the board lock, not at request arrival.
    Instant admittedAt = clock.instant();
    if (!admittedAt.isBefore(closesAt)) {
      JsonNode result = rejection(puzzle, command, "PUZZLE_CLOSED", "Puzzle is closed");
      saveResult(subject, puzzle, command, canonical, result);
      return result;
    }
    admitted.accept(admittedAt);
    JsonNode definition = json((String) row.get("definition"));
    long version = ((Number) row.get("board_version")).longValue();
    long definitionVersion = ((Number) row.get("definition_version")).longValue();
    ObjectNode current = snapshot(puzzle, definitionVersion, version);
    for (JsonNode group : current.path("groups")) {
      if (group.path("transform").path("rotation").intValue() % 90000 != 0) {
        JsonNode result =
            rejection(puzzle, command, "INVALID_ACTION", "UNSUPPORTED_LEGACY_ORIENTATION");
        saveResult(subject, puzzle, command, canonical, result);
        return result;
      }
    }
    jdbc.update(
        "INSERT INTO game_allowances(subject_id, puzzle_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        subject,
        puzzle);
    int used =
        jdbc.queryForObject(
            "SELECT used FROM game_allowances WHERE subject_id = ? AND puzzle_id = ? FOR UPDATE",
            Integer.class,
            subject,
            puzzle);
    Plan plan;
    try {
      plan = planner.apply(definition.deepCopy(), current.deepCopy(), 5 - used);
    } catch (Rejected rejected) {
      JsonNode result = rejection(puzzle, command, rejected.code, rejected.getMessage());
      saveResult(subject, puzzle, command, canonical, result);
      return result;
    }
    if (plan == null || plan.snapshot == null || plan.charged < 0 || plan.charged > 5 - used) {
      throw new IllegalStateException("invalid trusted plan");
    }
    JsonNode next = plan.snapshot;
    validator.validate(definition, next);
    if (!puzzle.equals(BoardValidator.id(next, "puzzleId"))
        || next.path("definitionVersion").longValue() != definitionVersion) {
      throw new IllegalStateException("plan changed board identity");
    }
    long nextVersion = next.path("boardVersion").longValue();
    boolean changed = !sameGroups(current, next);
    if ((changed && (version == MAX_SAFE || nextVersion != version + 1))
        || (!changed && (nextVersion != version || plan.charged != 0))) {
      throw new IllegalStateException("invalid board version or no-op charge");
    }
    if (LocalGeometry.hasForbiddenOverlap(definition, next)) {
      JsonNode result = rejection(puzzle, command, "INVALID_ACTION", "Forbidden piece overlap");
      saveResult(subject, puzzle, command, canonical, result);
      return result;
    }
    if (changed) {
      saveSnapshot(puzzle, current, next);
      jdbc.update(
          "UPDATE game_boards SET board_version = ? WHERE puzzle_id = ?", nextVersion, puzzle);
      ObjectNode event = mapper.createObjectNode();
      event.put("schemaVersion", 1).put("type", "boardChanged").put("puzzleId", puzzle.toString());
      event
          .put("boardVersion", nextVersion)
          .put("eventId", UUID.randomUUID().toString())
          .put("commandId", command.toString());
      ArrayNode removed = event.putArray("removedGroupIds");
      for (JsonNode group : current.path("groups")) removed.add(group.path("groupId").textValue());
      event.set("groups", next.path("groups"));
      jdbc.update(
          "INSERT INTO game_events(puzzle_id, board_version, event_id, event) VALUES (?, ?, ?::uuid, ?::jsonb)",
          puzzle,
          nextVersion,
          event.path("eventId").textValue(),
          event.toString());
    }
    int newUsed = used + plan.charged;
    jdbc.update(
        "UPDATE game_allowances SET used = ? WHERE subject_id = ? AND puzzle_id = ?",
        newUsed,
        subject,
        puzzle);
    ObjectNode result = mapper.createObjectNode();
    result
        .put("schemaVersion", 1)
        .put("puzzleId", puzzle.toString())
        .put("commandId", command.toString());
    result.put("outcome", "confirmed").put("boardVersion", nextVersion);
    result
        .putObject("allowance")
        .put("limit", 5)
        .put("used", newUsed)
        .put("remaining", 5 - newUsed);
    result.putNull("error");
    saveResult(subject, puzzle, command, canonical, result);
    // Normalize numeric node types the same way as a JSONB replay after restart.
    return json(result.toString());
  }

  /** Returns a retained result or null; a different canonical payload yields a conflict result. */
  @Transactional(readOnly = true)
  public JsonNode lookup(UUID subject, UUID puzzle, UUID command, JsonNode payload) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT canonical_payload, result::text AS result FROM game_commands WHERE subject_id = ? AND command_id = ?",
            subject,
            command);
    if (rows.isEmpty()) return null;
    String canonical;
    try {
      canonical = new JsonCanonicalizer(payload.toString()).getEncodedString();
    } catch (IOException ex) {
      throw new IllegalArgumentException("invalid payload", ex);
    }
    if (!canonical.equals(rows.getFirst().get("canonical_payload")))
      return rejection(
          puzzle,
          command,
          "COMMAND_CONFLICT",
          "Command ID was already used with different content");
    return json((String) rows.getFirst().get("result"));
  }

  @Transactional(readOnly = true)
  public JsonNode find(UUID subject, UUID command) {
    List<String> rows =
        jdbc.query(
            "SELECT result::text FROM game_commands WHERE subject_id = ? AND command_id = ?",
            (rs, row) -> rs.getString(1),
            subject,
            command);
    return rows.isEmpty() ? null : json(rows.getFirst());
  }

  @Transactional(readOnly = true)
  public JsonNode confirmedForTurn(UUID subject, UUID turn) {
    List<String> rows =
        jdbc.query(
            "SELECT result::text FROM game_commands WHERE subject_id = ? AND lower(canonical_payload::jsonb->>'turnId') = ? AND result->>'outcome' = 'confirmed' LIMIT 1",
            (rs, row) -> rs.getString(1),
            subject,
            turn.toString());
    return rows.isEmpty() ? null : json(rows.getFirst());
  }

  private ObjectNode snapshot(UUID puzzle, long definitionVersion, long version) {
    ObjectNode snapshot = mapper.createObjectNode();
    snapshot
        .put("schemaVersion", 1)
        .put("puzzleId", puzzle.toString())
        .put("definitionVersion", definitionVersion)
        .put("boardVersion", version);
    ArrayNode groups = snapshot.putArray("groups");
    Map<UUID, ObjectNode> byId = new HashMap<>();
    jdbc.query(
        "SELECT g.group_id, g.revision, g.translation_x, g.translation_y, g.rotation, g.final_position, m.piece_id FROM game_groups g JOIN game_group_pieces m ON m.puzzle_id = g.puzzle_id AND m.group_id = g.group_id WHERE g.puzzle_id = ? ORDER BY g.group_id, m.piece_id",
        rs -> {
          UUID id = rs.getObject("group_id", UUID.class);
          ObjectNode group = byId.get(id);
          if (group == null) {
            group = groups.addObject();
            byId.put(id, group);
            group.put("groupId", id.toString()).put("revision", rs.getLong("revision"));
            group.putArray("pieceIds");
            ObjectNode transform = group.putObject("transform");
            transform
                .putObject("translation")
                .put("x", rs.getInt("translation_x"))
                .put("y", rs.getInt("translation_y"));
            transform.put("rotation", rs.getInt("rotation"));
            group.put("finalPosition", rs.getBoolean("final_position"));
          }
          ((ArrayNode) group.path("pieceIds")).add(rs.getObject("piece_id", UUID.class).toString());
        },
        puzzle);
    return snapshot;
  }

  private boolean sameGroups(JsonNode left, JsonNode right) {
    Map<UUID, JsonNode> a = groupsById(left), b = groupsById(right);
    if (!a.keySet().equals(b.keySet())) return false;
    for (UUID id : a.keySet()) if (!sameGroup(a.get(id), b.get(id))) return false;
    return true;
  }

  private Map<UUID, JsonNode> groupsById(JsonNode snapshot) {
    Map<UUID, JsonNode> groups = new HashMap<>();
    for (JsonNode group : snapshot.path("groups"))
      groups.put(BoardValidator.id(group, "groupId"), group);
    return groups;
  }

  private Set<UUID> members(JsonNode group) {
    Set<UUID> ids = new HashSet<>();
    for (JsonNode piece : group.path("pieceIds")) ids.add(UUID.fromString(piece.textValue()));
    return ids;
  }

  private boolean sameGroup(JsonNode a, JsonNode b) {
    JsonNode x = a.path("transform"), y = b.path("transform");
    return a.path("revision").longValue() == b.path("revision").longValue()
        && a.path("finalPosition").booleanValue() == b.path("finalPosition").booleanValue()
        && x.path("rotation").longValue() == y.path("rotation").longValue()
        && x.path("translation").path("x").longValue()
            == y.path("translation").path("x").longValue()
        && x.path("translation").path("y").longValue()
            == y.path("translation").path("y").longValue()
        && members(a).equals(members(b));
  }

  private void saveSnapshot(UUID puzzle, JsonNode before, JsonNode after) {
    Map<UUID, JsonNode> oldGroups = groupsById(before), newGroups = groupsById(after);
    for (var entry : oldGroups.entrySet()) {
      if (!newGroups.containsKey(entry.getKey())) {
        jdbc.update(
            "DELETE FROM game_group_pieces WHERE puzzle_id = ? AND group_id = ?",
            puzzle,
            entry.getKey());
        jdbc.update(
            "DELETE FROM game_groups WHERE puzzle_id = ? AND group_id = ?", puzzle, entry.getKey());
      }
    }
    for (var entry : newGroups.entrySet()) {
      UUID id = entry.getKey();
      JsonNode group = entry.getValue(), previous = oldGroups.get(id);
      JsonNode transform = group.path("transform");
      if (previous != null) {
        if (sameGroup(previous, group)) continue;
        if (!members(previous).equals(members(group))
            || previous.path("revision").longValue() >= 9_007_199_254_740_991L
            || group.path("revision").longValue() != previous.path("revision").longValue() + 1)
          throw new IllegalStateException("Invalid retained group revision or membership");
        jdbc.update(
            "UPDATE game_groups SET revision = ?, translation_x = ?, translation_y = ?, rotation = ?, final_position = ? WHERE puzzle_id = ? AND group_id = ?",
            group.path("revision").longValue(),
            transform.path("translation").path("x").intValue(),
            transform.path("translation").path("y").intValue(),
            transform.path("rotation").intValue(),
            group.path("finalPosition").booleanValue(),
            puzzle,
            id);
      } else {
        if (group.path("revision").longValue() != 0)
          throw new IllegalStateException("New group revision must be zero");
        jdbc.update(
            "INSERT INTO game_groups(puzzle_id, group_id, revision, translation_x, translation_y, rotation, final_position) VALUES (?, ?, ?, ?, ?, ?, ?)",
            puzzle,
            id,
            0,
            transform.path("translation").path("x").intValue(),
            transform.path("translation").path("y").intValue(),
            transform.path("rotation").intValue(),
            group.path("finalPosition").booleanValue());
        for (JsonNode piece : group.path("pieceIds"))
          jdbc.update(
              "INSERT INTO game_group_pieces(puzzle_id, group_id, piece_id) VALUES (?, ?, ?::uuid)",
              puzzle,
              id,
              piece.textValue());
      }
    }
  }

  private void saveResult(
      UUID subject, UUID puzzle, UUID command, String payload, JsonNode result) {
    jdbc.update(
        "INSERT INTO game_commands(subject_id, puzzle_id, command_id, canonical_payload, result) VALUES (?, ?, ?, ?, ?::jsonb)",
        subject,
        puzzle,
        command,
        payload,
        result.toString());
  }

  private JsonNode rejection(UUID puzzle, UUID command, String code, String message) {
    ObjectNode result = mapper.createObjectNode();
    result
        .put("schemaVersion", 1)
        .put("puzzleId", puzzle.toString())
        .put("commandId", command.toString())
        .put("outcome", "rejected");
    result.putNull("boardVersion");
    result.putNull("allowance");
    result
        .putObject("error")
        .put("code", code)
        .put("message", message)
        .put("requestId", UUID.randomUUID().toString())
        .put("retryable", false);
    return result;
  }

  private JsonNode json(String value) {
    try {
      return mapper.readTree(value);
    } catch (IOException ex) {
      throw new IllegalStateException("invalid stored JSON", ex);
    }
  }
}
