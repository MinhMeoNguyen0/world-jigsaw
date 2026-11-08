package com.worldjigsaw.game;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GameReadController {
  private final LocalSessions sessions;
  private final GameCoordinator coordinator;

  GameReadController(LocalSessions sessions, GameCoordinator coordinator) {
    this.sessions = sessions;
    this.coordinator = coordinator;
  }

  @GetMapping("/api/v1/game/current")
  Map<String, Object> current(HttpServletRequest request, HttpServletResponse response) {
    LocalSessions.Session session = sessions.lookup(LocalSessions.cookie(request));
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Vary", "Cookie");
    return coordinator.current(session == null ? null : session.subject());
  }

  @ExceptionHandler({
    IllegalStateException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<Map<String, Object>> unavailableWriter() {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .header("Cache-Control", "no-store")
        .body(
            Map.of(
                "schemaVersion",
                1,
                "error",
                Map.of(
                    "code",
                    "INTERNAL_ERROR",
                    "message",
                    "Game state is temporarily unavailable",
                    "requestId",
                    UUID.randomUUID().toString(),
                    "retryable",
                    true)));
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
