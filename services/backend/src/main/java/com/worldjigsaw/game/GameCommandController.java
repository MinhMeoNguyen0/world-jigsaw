package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
class GameCommandController {
  private final LocalSessions sessions;
  private final GameWireValidator wire;
  private final GameCoordinator game;
  private final ObjectMapper mapper = new ObjectMapper();

  GameCommandController(LocalSessions sessions, GameWireValidator wire, GameCoordinator game) {
    this.sessions = sessions;
    this.wire = wire;
    this.game = game;
  }

  @PostMapping("/api/v1/game/turns")
  ResponseEntity<Map<?, ?>> begin(HttpServletRequest request) throws IOException {
    UUID subject = subject(request, true);
    return response(game.begin(subject, wire.parse("BeginTurn", body(request))));
  }

  @PostMapping("/api/v1/game/turns/{turnId}/confirm")
  ResponseEntity<Map<?, ?>> confirm(@PathVariable UUID turnId, HttpServletRequest request)
      throws IOException {
    UUID subject = subject(request, true);
    String raw = body(request);
    JsonNode payload;
    try {
      payload = wire.parse("ConfirmV2", raw);
    } catch (IllegalArgumentException notV2) {
      payload = wire.parse("Confirm", raw);
    }
    verifyTurn(turnId, payload);
    return response(game.confirm(subject, payload));
  }

  @PostMapping("/api/v1/game/turns/{turnId}/cancel")
  ResponseEntity<Map<?, ?>> cancel(@PathVariable UUID turnId, HttpServletRequest request)
      throws IOException {
    UUID subject = subject(request, true);
    JsonNode payload = wire.parse("Cancel", body(request));
    verifyTurn(turnId, payload);
    return response(game.cancel(subject, payload));
  }

  @PostMapping("/api/v1/game/turns/{turnId}/undo")
  ResponseEntity<Map<?, ?>> undo(@PathVariable UUID turnId, HttpServletRequest request)
      throws IOException {
    UUID subject = subject(request, true);
    JsonNode payload = wire.parse("UndoV2", body(request));
    verifyTurn(turnId, payload);
    return response(game.undo(subject, payload));
  }

  @GetMapping("/api/v1/game/commands/{commandId}")
  ResponseEntity<Map<?, ?>> command(
      @PathVariable UUID commandId,
      @RequestParam(required = false) UUID puzzleId,
      HttpServletRequest request) {
    UUID subject = subject(request, false);
    JsonNode known = game.commandStatus(subject, commandId);
    if (known != null) return response(known);
    if (puzzleId == null) {
      Map<String, Object> current = game.current(subject);
      puzzleId = UUID.fromString(((Map<?, ?>) current.get("snapshot")).get("puzzleId").toString());
    }
    ObjectNode result =
        mapper
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("puzzleId", puzzleId.toString())
            .put("commandId", commandId.toString())
            .put("outcome", "unknown");
    result.putNull("boardVersion");
    result.putNull("allowance");
    result.putNull("error");
    return response(result);
  }

  // Spring Boot's HTTP mapper is not the Jackson 2 mapper used by our contract validator.
  // Convert to ordinary JSON values so the tree is not serialized as Java bean metadata.
  private ResponseEntity<Map<?, ?>> response(JsonNode value) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(mapper.convertValue(value, Map.class));
  }

  private UUID subject(HttpServletRequest request, boolean originRequired) {
    if (originRequired && !LocalSessions.sameOrigin(request))
      throw new HttpFailure("FORBIDDEN", "Origin is not allowed");
    LocalSessions.Session session = sessions.lookup(LocalSessions.cookie(request));
    if (session == null) throw new HttpFailure("UNAUTHENTICATED", "Session is required");
    return session.subject();
  }

  private void verifyTurn(UUID id, JsonNode payload) {
    if (!id.equals(BoardValidator.id(payload, "turnId")))
      throw new IllegalArgumentException("Turn mismatch");
  }

  private String body(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > 65536)
      throw new HttpFailure("MESSAGE_TOO_LARGE", "Request is too large");
    byte[] bytes = request.getInputStream().readNBytes(65537);
    if (bytes.length > 65536) throw new HttpFailure("MESSAGE_TOO_LARGE", "Request is too large");
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ex) {
      throw new IllegalArgumentException("Invalid UTF-8", ex);
    }
  }
}

class HttpFailure extends RuntimeException {
  final String code;

  HttpFailure(String code, String message) {
    super(message);
    this.code = code;
  }
}

@RestControllerAdvice(assignableTypes = GameCommandController.class)
class GameCommandErrors {
  @ExceptionHandler({
    HttpFailure.class,
    DraftRules.RuleError.class,
    TurnLeaseRegistry.LeaseError.class,
    PuzzleUnavailable.class,
    IllegalArgumentException.class,
    IllegalStateException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<Map<String, Object>> handle(Exception exception) {
    String code =
        exception instanceof HttpFailure e
            ? e.code
            : exception instanceof DraftRules.RuleError e
                ? e.code
                : exception instanceof TurnLeaseRegistry.LeaseError e
                    ? e.code
                    : exception instanceof PuzzleUnavailable
                        ? "PUZZLE_UNAVAILABLE"
                        : exception instanceof IllegalArgumentException
                            ? "INVALID_REQUEST"
                            : "INTERNAL_ERROR";
    String message =
        exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException
                || exception instanceof org.springframework.dao.DataAccessException
            ? "Unable to process request"
            : exception.getMessage();
    if ("UNSUPPORTED_LEGACY_ORIENTATION".equals(code)) {
      code = "INVALID_ACTION";
      message = "UNSUPPORTED_LEGACY_ORIENTATION";
    }
    if (message == null || message.isBlank()) message = "Unable to process request";
    HttpStatus status =
        switch (code) {
          case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
          case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
          case "MESSAGE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
          case "PUZZLE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
          case "PIECE_BUSY",
              "STALE_REVISION",
              "QUOTA_EXCEEDED",
              "TURN_CLOSED",
              "PUZZLE_CLOSED",
              "COMMAND_CONFLICT" ->
              HttpStatus.CONFLICT;
          case "INTERNAL_ERROR" -> HttpStatus.INTERNAL_SERVER_ERROR;
          default -> HttpStatus.BAD_REQUEST;
        };
    boolean retryable =
        status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(
            Map.of(
                "schemaVersion",
                1,
                "error",
                Map.of(
                    "code",
                    code,
                    "message",
                    message,
                    "requestId",
                    UUID.randomUUID().toString(),
                    "retryable",
                    retryable)));
  }
}
