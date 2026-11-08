package com.worldjigsaw.game;

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

  GameReadController(GameBoards boards) {
    this.boards = boards;
  }

  @GetMapping("/api/v1/game/current")
  Map<String, Object> current() {
    return boards.current();
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
