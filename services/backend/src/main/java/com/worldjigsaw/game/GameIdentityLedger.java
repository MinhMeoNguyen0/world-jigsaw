package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable non-reusable identifiers and request receipts; not a command endpoint. */
@Service
class GameIdentityLedger {
  private final JdbcTemplate jdbc;
  private final GameWriterGuard writer;
  private final ObjectMapper mapper = new ObjectMapper();

  record Receipt(boolean conflict, JsonNode result) {}

  GameIdentityLedger(JdbcTemplate jdbc, GameWriterGuard writer) {
    this.jdbc = jdbc;
    this.writer = writer;
  }

  @Transactional
  public UUID reserveTurn(UUID subject, UUID puzzle) {
    writer.transactionBarrier(jdbc);
    for (int i = 0; i < 10; i++) {
      UUID id = UUID.randomUUID();
      if (jdbc.update(
              "INSERT INTO game_turn_ids(turn_id, puzzle_id, subject_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
              id,
              puzzle,
              subject)
          == 1) return id;
    }
    throw new IllegalStateException("Unable to allocate turn ID");
  }

  @Transactional
  public UUID reserveGroup(UUID puzzle) {
    writer.transactionBarrier(jdbc);
    for (int i = 0; i < 10; i++) {
      UUID id = UUID.randomUUID();
      if (jdbc.update(
              "INSERT INTO game_group_ids(puzzle_id, group_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
              puzzle,
              id)
          == 1) return id;
    }
    throw new IllegalStateException("Unable to allocate group ID");
  }

  /** The caller must serialize the decision and apply it consistently with this receipt. */
  @Transactional
  public Receipt record(
      UUID subject, UUID puzzle, UUID requestId, String kind, JsonNode payload, JsonNode result) {
    writer.transactionBarrier(jdbc);
    if ((!"begin".equals(kind) && !"cancel".equals(kind) && !"undo".equals(kind))
        || !puzzle.equals(BoardValidator.id(payload, "puzzleId"))
        || !requestId.equals(BoardValidator.id(payload, "requestId")))
      throw new IllegalArgumentException("Invalid request identity");
    String canonical;
    try {
      canonical = new JsonCanonicalizer(payload.toString()).getEncodedString();
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid request", ex);
    }
    jdbc.queryForObject(
        "SELECT subject_id FROM game_subjects WHERE subject_id = ? FOR UPDATE",
        UUID.class,
        subject);
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT request_kind, canonical_payload, result::text AS result FROM game_requests WHERE subject_id = ? AND request_id = ?",
            subject,
            requestId);
    if (!existing.isEmpty()) {
      Map<String, Object> row = existing.getFirst();
      if (!kind.equals(row.get("request_kind")) || !canonical.equals(row.get("canonical_payload")))
        return new Receipt(true, null);
      return new Receipt(false, json((String) row.get("result")));
    }
    jdbc.update(
        "INSERT INTO game_requests(subject_id, request_id, puzzle_id, request_kind, canonical_payload, result) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
        subject,
        requestId,
        puzzle,
        kind,
        canonical,
        result.toString());
    return new Receipt(false, json(result.toString()));
  }

  @Transactional(timeout = 30)
  public Receipt lookup(UUID subject, UUID requestId, String kind, JsonNode payload) {
    jdbc.queryForList("SELECT subject_id FROM game_subjects WHERE subject_id = ? FOR UPDATE", subject);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT request_kind, canonical_payload, result::text AS result FROM game_requests WHERE subject_id = ? AND request_id = ?",
            subject,
            requestId);
    if (rows.isEmpty()) return null;
    String canonical;
    try {
      canonical = new JsonCanonicalizer(payload.toString()).getEncodedString();
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid request", ex);
    }
    Map<String, Object> row = rows.getFirst();
    if (!kind.equals(row.get("request_kind")) || !canonical.equals(row.get("canonical_payload")))
      return new Receipt(true, null);
    return new Receipt(false, json((String) row.get("result")));
  }

  @Transactional(readOnly = true)
  public boolean ownsTurn(UUID subject, UUID puzzle, UUID turn) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM game_turn_ids WHERE subject_id = ? AND puzzle_id = ? AND turn_id = ?",
            Integer.class,
            subject,
            puzzle,
            turn);
    return count != null && count == 1;
  }

  private JsonNode json(String value) {
    try {
      return mapper.readTree(value);
    } catch (IOException ex) {
      throw new IllegalStateException("Invalid stored JSON", ex);
    }
  }
}
