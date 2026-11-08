package com.worldjigsaw.game;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GameReadController {
  private final GameBoards boards;
  private final LocalSessions sessions;

  GameReadController(GameBoards boards, LocalSessions sessions) {
    this.boards = boards;
    this.sessions = sessions;
  }

  @GetMapping("/api/v1/game/current")
  Map<String, Object> current(HttpServletRequest request) {
    LocalSessions.Session session = sessions.lookup(LocalSessions.cookie(request));
    return boards.currentFor(session == null ? null : session.subject());
  }

  @ExceptionHandler(PuzzleUnavailable.class)
  ResponseEntity<Map<String, Object>> unavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            Map.of(
                "schemaVersion",
                1,
                "error",
                Map.of(
                    "code",
                    "PUZZLE_UNAVAILABLE",
                    "message",
                    "Today's puzzle is unavailable",
                    "requestId",
                    UUID.randomUUID().toString(),
                    "retryable",
                    true)));
  }
}
