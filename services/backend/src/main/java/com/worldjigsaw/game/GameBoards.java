package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Configuration
class GameClockConfiguration {
  @Bean
  Clock gameClock() {
    return Clock.systemUTC();
  }
}

@Service
public class GameBoards {
  private final JdbcTemplate jdbc;
  private final BoardValidator validator;
  private final Clock clock;
  private final ObjectMapper mapper = new ObjectMapper();

  GameBoards(JdbcTemplate jdbc, BoardValidator validator, Clock clock) {
    this.jdbc = jdbc;
    this.validator = validator;
    this.clock = clock;
  }

  /** Trusted administrative import. Day is assigned by the caller, never inferred from a client. */
  @Transactional
  public void importBoard(LocalDate day, JsonNode definition, JsonNode snapshot) {
    validator.validate(definition, snapshot);
    UUID puzzle = BoardValidator.id(definition, "puzzleId");
    jdbc.update(
        "INSERT INTO puzzles(id, puzzle_day) VALUES (?, ?) ON CONFLICT DO NOTHING", puzzle, day);
    Integer identity =
        jdbc.queryForObject(
            "SELECT count(*) FROM puzzles WHERE id = ? AND puzzle_day = ?",
            Integer.class,
            puzzle,
            day);
    if (identity == null || identity != 1) {
      throw new IllegalArgumentException("puzzle identity or day already assigned");
    }
    jdbc.update(
        "INSERT INTO game_boards(puzzle_id, definition_version, definition, board_version, closes_at) VALUES (?, ?, ?::jsonb, ?, ?)",
        puzzle,
        definition.path("definitionVersion").longValue(),
        definition.toString(),
        snapshot.path("boardVersion").longValue(),
        day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    for (JsonNode piece : definition.path("pieces")) {
      jdbc.update(
          "INSERT INTO game_pieces(puzzle_id, piece_id) VALUES (?, ?)",
          puzzle,
          BoardValidator.id(piece, "pieceId"));
    }
    for (JsonNode group : snapshot.path("groups")) {
      UUID groupId = BoardValidator.id(group, "groupId");
      JsonNode transform = group.path("transform");
      jdbc.update(
          "INSERT INTO game_groups(puzzle_id, group_id, revision, translation_x, translation_y, rotation, final_position) VALUES (?, ?, ?, ?, ?, ?, ?)",
          puzzle,
          groupId,
          group.path("revision").longValue(),
          transform.path("translation").path("x").intValue(),
          transform.path("translation").path("y").intValue(),
          transform.path("rotation").intValue(),
          group.path("finalPosition").booleanValue());
      for (JsonNode pieceId : group.path("pieceIds")) {
        jdbc.update(
            "INSERT INTO game_group_pieces(puzzle_id, group_id, piece_id) VALUES (?, ?, ?)",
            puzzle,
            groupId,
            UUID.fromString(pieceId.textValue()));
      }
    }
  }

  /** One repeatable-read transaction holds definition, version and all memberships together. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> current() {
    Instant now = clock.instant();
    LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
    var boards =
        jdbc.query(
            "SELECT p.id, b.definition::text, b.definition_version, b.board_version, b.closes_at FROM puzzles p JOIN game_boards b ON b.puzzle_id = p.id WHERE p.puzzle_day = ?",
            (rs, row) -> {
              try {
                return new Board(
                    rs.getObject("id", UUID.class),
                    mapper.readValue(rs.getString("definition"), Map.class),
                    rs.getLong("definition_version"),
                    rs.getLong("board_version"),
                    rs.getTimestamp("closes_at").toInstant());
              } catch (Exception e) {
                throw new IllegalStateException("invalid saved definition", e);
              }
            },
            day);
    if (boards.isEmpty()) throw new PuzzleUnavailable();
    Board board = boards.getFirst();
    List<Map<String, Object>> groups = new ArrayList<>();
    jdbc.query(
        "SELECT g.group_id, g.revision, g.translation_x, g.translation_y, g.rotation, g.final_position, m.piece_id FROM game_groups g JOIN game_group_pieces m ON m.puzzle_id = g.puzzle_id AND m.group_id = g.group_id WHERE g.puzzle_id = ? ORDER BY g.group_id, m.piece_id",
        rs -> {
          UUID groupId = rs.getObject("group_id", UUID.class);
          Map<String, Object> group;
          if (groups.isEmpty() || !groups.getLast().get("groupId").equals(groupId.toString())) {
            group = new LinkedHashMap<>();
            group.put("groupId", groupId.toString());
            group.put("revision", rs.getLong("revision"));
            group.put("pieceIds", new ArrayList<String>());
            group.put(
                "transform",
                Map.of(
                    "translation",
                    Map.of("x", rs.getInt("translation_x"), "y", rs.getInt("translation_y")),
                    "rotation",
                    rs.getInt("rotation")));
            group.put("finalPosition", rs.getBoolean("final_position"));
            groups.add(group);
          } else group = groups.getLast();
          @SuppressWarnings("unchecked")
          List<String> pieceIds = (List<String>) group.get("pieceIds");
          pieceIds.add(rs.getObject("piece_id", UUID.class).toString());
        },
        board.id());
    Map<String, Object> snapshot =
        Map.of(
            "schemaVersion",
            1,
            "puzzleId",
            board.id().toString(),
            "definitionVersion",
            board.definitionVersion(),
            "boardVersion",
            board.boardVersion(),
            "groups",
            groups);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("day", day.toString());
    result.put("serverTime", now.toString());
    result.put("endsAt", board.closesAt().toString());
    result.put("definition", board.definition());
    result.put("snapshot", snapshot);
    result.put("allowance", null);
    result.put("ownTurn", null);
    return result;
  }

  private record Board(
      UUID id, Map<?, ?> definition, long definitionVersion, long boardVersion, Instant closesAt) {}
}

class PuzzleUnavailable extends RuntimeException {}
