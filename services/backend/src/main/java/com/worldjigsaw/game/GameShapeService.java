package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes only generated contours, under the board lock, with durable version and request history.
 */
@Service
class GameShapeService {
  private static final long MAX_SAFE = 9_007_199_254_740_991L;
  private final JdbcTemplate jdbc;
  private final GameBoards boards;
  private final BoardValidator validator;
  private final DraftRules rules;
  private final GameWriterGuard writer;
  private final Clock clock;
  private final ObjectMapper mapper = new ObjectMapper();

  GameShapeService(
      JdbcTemplate jdbc,
      GameBoards boards,
      BoardValidator validator,
      DraftRules rules,
      GameWriterGuard writer,
      Clock clock) {
    this.jdbc = jdbc;
    this.boards = boards;
    this.validator = validator;
    this.rules = rules;
    this.writer = writer;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public JsonNode lookup(UUID subject, JsonNode payload) {
    UUID request = BoardValidator.id(payload, "requestId");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT canonical_payload, result::text AS result FROM game_shape_requests WHERE subject_id = ? AND request_id = ?",
            subject,
            request);
    if (rows.isEmpty()) return null;
    if (!canonical(payload).equals(rows.getFirst().get("canonical_payload")))
      throw new HttpFailure("COMMAND_CONFLICT", "Shape request ID was reused");
    return json((String) rows.getFirst().get("result"));
  }

  @Transactional(timeout = 30)
  public JsonNode apply(UUID subject, JsonNode payload) {
    writer.transactionBarrier(jdbc);
    UUID puzzle = BoardValidator.id(payload, "puzzleId"),
        request = BoardValidator.id(payload, "requestId");
    jdbc.queryForObject(
        "SELECT subject_id FROM game_subjects WHERE subject_id = ? FOR UPDATE",
        UUID.class,
        subject);
    JsonNode cached = lookup(subject, payload);
    if (cached != null) return cached;
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT definition::text AS definition, definition_version, board_version, closes_at FROM game_boards WHERE puzzle_id = ? FOR UPDATE",
            puzzle);
    if (rows.isEmpty()) throw new HttpFailure("PUZZLE_UNAVAILABLE", "Puzzle is unavailable");
    Map<String, Object> row = rows.getFirst();
    long version = ((Number) row.get("definition_version")).longValue(),
        boardVersion = ((Number) row.get("board_version")).longValue();
    if (!clock.instant().isBefore(((java.sql.Timestamp) row.get("closes_at")).toInstant()))
      throw new HttpFailure("PUZZLE_CLOSED", "Puzzle is closed");
    if (payload.path("definitionVersion").longValue() != version)
      throw new HttpFailure("STALE_REVISION", "Puzzle shape has changed; refresh before applying");
    JsonNode current = mapper.valueToTree(boards.currentFor(null));
    if (!puzzle.equals(BoardValidator.id(current.path("snapshot"), "puzzleId")))
      throw new HttpFailure("PUZZLE_UNAVAILABLE", "Puzzle is unavailable");
    ObjectNode definition = (ObjectNode) json((String) row.get("definition"));
    JsonNode oldShape = definition.path("shape");
    int depth = payload.path("tabDepthPermille").intValue(),
        width = payload.path("tabWidthPermille").intValue(),
        wave = payload.path("edgeWavePermille").intValue();
    boolean changed =
        depth != oldShape.path("tabDepthPermille").intValue()
            || width != oldShape.path("tabWidthPermille").intValue()
            || wave != oldShape.path("edgeWavePermille").intValue();
    if (changed) {
      if (version == MAX_SAFE || boardVersion == MAX_SAFE)
        throw new HttpFailure("INVALID_ACTION", "Puzzle version exhausted");
      reshape(definition, depth, width, wave);
      definition.put("definitionVersion", version + 1);
      definition.put("checksum", checksum(definition));
      ObjectNode snapshot = (ObjectNode) current.path("snapshot").deepCopy();
      snapshot.put("definitionVersion", version + 1);
      snapshot.put("boardVersion", boardVersion + 1);
      try {
        validator.validate(definition, snapshot);
        rules.begin(definition, snapshot, 5);
        if (LocalGeometry.hasForbiddenOverlap(definition, snapshot))
          throw new HttpFailure("INVALID_ACTION", "Shape would overlap existing pieces");
      } catch (HttpFailure ex) {
        throw ex;
      } catch (RuntimeException ex) {
        throw new HttpFailure("INVALID_ACTION", "Shape is incompatible with this board");
      }
      jdbc.update(
          "INSERT INTO game_definition_history(puzzle_id, definition_version, board_version, definition) VALUES (?, ?, ?, ?::jsonb)",
          puzzle,
          version + 1,
          boardVersion + 1,
          definition.toString());
      jdbc.update(
          "UPDATE game_boards SET definition_version = ?, board_version = ?, definition = ?::jsonb WHERE puzzle_id = ?",
          version + 1,
          boardVersion + 1,
          definition.toString(),
          puzzle);
      version++;
      boardVersion++;
    }
    ObjectNode result =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzle.toString())
            .put("requestId", request.toString())
            .put("outcome", changed ? "updated" : "unchanged")
            .put("definitionVersion", version)
            .put("boardVersion", boardVersion);
    jdbc.update(
        "INSERT INTO game_shape_requests(subject_id, request_id, puzzle_id, canonical_payload, result) VALUES (?, ?, ?, ?, ?::jsonb)",
        subject,
        request,
        puzzle,
        canonical(payload),
        result.toString());
    return result;
  }

  private record Point(long x, long y) {}

  private record Segment(Point c1, Point c2, Point to) {}

  /** Rebuild reciprocal grid seams from canonical endpoints, preserving their tab direction. */
  void reshape(ObjectNode definition, int depth, int width, int wave) {
    String generator = definition.path("shape").path("generatorVersion").asText();
    if (!generator.matches("world-jigsaw-grid-[3-7]"))
      throw new HttpFailure("INVALID_ACTION", "Shape editing is unavailable for this puzzle");
    long scale = Long.MAX_VALUE;
    for (JsonNode piece : definition.path("pieces")) {
      JsonNode ref = piece.path("referencePoint");
      scale = Math.min(scale, Math.min(ref.path("x").longValue(), ref.path("y").longValue()) * 2);
    }
    if (scale < 1000 || scale == Long.MAX_VALUE)
      throw new HttpFailure("INVALID_ACTION", "Unsupported piece size");
    for (JsonNode piece : definition.path("pieces")) {
      Point origin = point(piece.path("solvedPosition"));
      for (JsonNode raw : piece.path("edges")) {
        ObjectNode edge = (ObjectNode) raw;
        if (edge.path("neighborPieceId").isNull()) continue;
        JsonNode segments = edge.path("segments");
        if (segments.size() != 3 || !segments.isArray())
          throw new HttpFailure("INVALID_ACTION", "Unsupported piece contour");
        for (JsonNode segment : segments)
          if (!"cubic".equals(segment.path("kind").asText()))
            throw new HttpFailure("INVALID_ACTION", "Unsupported piece contour");
        Point from = add(origin, point(edge.path("start"))),
            to = add(origin, point(segments.get(2).path("to")));
        if ((from.x == to.x) == (from.y == to.y))
          throw new HttpFailure("INVALID_ACTION", "Unsupported piece edge");
        boolean forward = from.x < to.x || (from.x == to.x && from.y < to.y);
        Point start = forward ? from : to, end = forward ? to : from;
        Point control = add(origin, point(segments.get(1).path("control1")));
        long cross =
            (end.x - start.x) * (control.y - start.y) - (end.y - start.y) * (control.x - start.x);
        if (cross == 0) throw new HttpFailure("INVALID_ACTION", "Unsupported tab direction");
        int sign = cross > 0 ? 1 : -1;
        double neckStart = .5 - width / 2000.0, neckEnd = .5 + width / 2000.0;
        double shoulder = scale * wave / 1000.0, tab = scale * depth / 1000.0 * 4 / 3;
        Segment[] canonical = {
          new Segment(
              at(start, end, neckStart * .36, shoulder, sign),
              at(start, end, neckStart * .76, shoulder, sign),
              at(start, end, neckStart, 0, sign)),
          new Segment(
              at(start, end, neckStart - .01, tab, sign),
              at(start, end, neckEnd + .01, tab, sign),
              at(start, end, neckEnd, 0, sign)),
          new Segment(
              at(start, end, neckEnd + (1 - neckEnd) * .24, shoulder, sign),
              at(start, end, neckEnd + (1 - neckEnd) * .64, shoulder, sign),
              end)
        };
        Point[] starts = {start, canonical[0].to, canonical[1].to};
        ArrayNode output = mapper.createArrayNode();
        for (int i = 0; i < 3; i++) {
          int index = forward ? i : 2 - i;
          Segment segment = canonical[index];
          ObjectNode value = output.addObject().put("kind", "cubic");
          value.set("control1", jsonPoint(sub(forward ? segment.c1 : segment.c2, origin)));
          value.set("control2", jsonPoint(sub(forward ? segment.c2 : segment.c1, origin)));
          value.set("to", jsonPoint(sub(forward ? segment.to : starts[index], origin)));
        }
        edge.set("segments", output);
      }
    }
    ObjectNode shape = (ObjectNode) definition.path("shape");
    shape.put("tabDepthPermille", depth);
    shape.put("tabWidthPermille", width);
    shape.put("edgeWavePermille", wave);
  }

  private static Point at(Point a, Point b, double along, double normal, int sign) {
    long dx = b.x - a.x, dy = b.y - a.y;
    return new Point(
        Math.round(a.x + dx * along - Long.signum(dy) * normal * sign),
        Math.round(a.y + dy * along + Long.signum(dx) * normal * sign));
  }

  private static Point point(JsonNode value) {
    return new Point(value.path("x").longValue(), value.path("y").longValue());
  }

  private static Point add(Point a, Point b) {
    return new Point(a.x + b.x, a.y + b.y);
  }

  private static Point sub(Point a, Point b) {
    return new Point(a.x - b.x, a.y - b.y);
  }

  private ObjectNode jsonPoint(Point p) {
    return mapper.createObjectNode().put("x", p.x).put("y", p.y);
  }

  private static String canonical(JsonNode value) {
    try {
      return new JsonCanonicalizer(value.toString()).getEncodedString();
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid request", ex);
    }
  }

  private String checksum(ObjectNode definition) {
    ObjectNode content = definition.deepCopy();
    content.remove("checksum");
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          new JsonCanonicalizer(mapper.writeValueAsString(content))
                              .getEncodedUTF8()));
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new IllegalArgumentException("Invalid definition", ex);
    }
  }

  private JsonNode json(String value) {
    try {
      return mapper.readTree(value);
    } catch (IOException ex) {
      throw new IllegalStateException("Invalid stored JSON", ex);
    }
  }
}
