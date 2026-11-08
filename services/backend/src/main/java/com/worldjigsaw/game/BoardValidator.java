package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

/** Validates the shared wire shape and the board invariants before any database writes. */
@Component
public class BoardValidator {
  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchema definitionSchema;
  private final JsonSchema snapshotSchema;

  public BoardValidator() throws IOException {
    JsonNode schema;
    try (var input = getClass().getResourceAsStream("/contracts/v1/schema.json")) {
      schema = mapper.readTree(input);
    }
    definitionSchema = schemaFor(schema, "Definition");
    snapshotSchema = schemaFor(schema, "Snapshot");
  }

  private JsonSchema schemaFor(JsonNode original, String type) {
    ObjectNode schema = original.deepCopy();
    schema.remove("oneOf");
    schema.put("$ref", "#/$defs/" + type);
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema);
  }

  public void validate(JsonNode definition, JsonNode snapshot) {
    require(definitionSchema.validate(definition).isEmpty(), "invalid definition shape");
    require(snapshotSchema.validate(snapshot).isEmpty(), "invalid snapshot shape");
    require(id(definition, "puzzleId").equals(id(snapshot, "puzzleId")), "puzzle mismatch");
    require(
        definition.path("definitionVersion").equals(snapshot.path("definitionVersion")),
        "version mismatch");
    verifyChecksum(definition);
    JsonNode workspace = definition.path("workspace");
    for (String name : new String[] {"imageBounds", "playSurface", "workspace"}) {
      JsonNode r = definition.path(name);
      for (String axis : new String[] {"x", "y"}) {
        long min = r.path("min").path(axis).longValue();
        long max = r.path("max").path(axis).longValue();
        require(min < max, "invalid bounds");
        require(
            min >= workspace.path("min").path(axis).longValue()
                && max <= workspace.path("max").path(axis).longValue(),
            "bounds outside workspace");
      }
    }
    Map<UUID, JsonNode> pieces = new HashMap<>();
    for (JsonNode piece : definition.path("pieces")) {
      require(pieces.put(id(piece, "pieceId"), piece) == null, "duplicate piece");
      Set<String> edges = new HashSet<>();
      JsonNode contours = piece.path("edges");
      for (int i = 0; i < contours.size(); i++) {
        JsonNode edge = contours.get(i);
        JsonNode next = contours.get((i + 1) % contours.size());
        JsonNode segments = edge.path("segments");
        require(
            segments.get(segments.size() - 1).path("to").equals(next.path("start")),
            "piece contour is not closed");
      }
      for (JsonNode edge : piece.path("edges")) {
        require(edges.add(edge.path("edgeId").textValue()), "duplicate edge");
        require(
            edge.path("neighborPieceId").isNull() == edge.path("neighborEdgeId").isNull(),
            "incomplete adjacency");
      }
    }
    for (JsonNode piece : pieces.values()) {
      for (JsonNode edge : piece.path("edges")) {
        if (edge.path("neighborPieceId").isNull()) continue;
        UUID neighborId = id(edge, "neighborPieceId");
        JsonNode other = pieces.get(neighborId);
        require(other != null && other != piece, "unknown adjacent piece");
        JsonNode back = null;
        for (JsonNode candidate : other.path("edges")) {
          if (candidate.path("edgeId").equals(edge.path("neighborEdgeId"))) back = candidate;
        }
        require(
            back != null
                && !back.path("neighborPieceId").isNull()
                && id(back, "neighborPieceId").equals(id(piece, "pieceId"))
                && back.path("neighborEdgeId").equals(edge.path("edgeId")),
            "non-reciprocal adjacency");
        require(reversedGeometry(piece, edge, other, back), "adjacent geometry mismatch");
      }
    }
    Set<UUID> groups = new HashSet<>();
    Set<UUID> seen = new HashSet<>();
    for (JsonNode group : snapshot.path("groups")) {
      require(groups.add(id(group, "groupId")), "duplicate group");
      for (JsonNode piece : group.path("pieceIds")) {
        UUID pieceId = UUID.fromString(piece.textValue());
        require(pieces.containsKey(pieceId) && seen.add(pieceId), "invalid piece membership");
      }
    }
    require(seen.size() == pieces.size(), "missing piece membership");
  }

  private static boolean reversedGeometry(JsonNode a, JsonNode edge, JsonNode b, JsonNode back) {
    JsonNode as = edge.path("segments"), bs = back.path("segments");
    if (as.size() != bs.size()) return false;
    for (int i = 0; i < as.size(); i++) {
      JsonNode x = as.get(i), y = bs.get(bs.size() - 1 - i);
      JsonNode startA = i == 0 ? edge.path("start") : as.get(i - 1).path("to");
      int j = bs.size() - 1 - i;
      JsonNode startB = j == 0 ? back.path("start") : bs.get(j - 1).path("to");
      if (!x.path("kind").equals(y.path("kind"))
          || !samePoint(startA, a, y.path("to"), b)
          || !samePoint(x.path("to"), a, startB, b)) return false;
      if (x.path("kind").asText().equals("cubic")
          && (!samePoint(x.path("control1"), a, y.path("control2"), b)
              || !samePoint(x.path("control2"), a, y.path("control1"), b))) return false;
    }
    return true;
  }

  private static boolean samePoint(JsonNode x, JsonNode a, JsonNode y, JsonNode b) {
    for (String axis : new String[] {"x", "y"}) {
      if (x.path(axis).longValue() + a.path("solvedPosition").path(axis).longValue()
          != y.path(axis).longValue() + b.path("solvedPosition").path(axis).longValue())
        return false;
    }
    return true;
  }

  private void verifyChecksum(JsonNode definition) {
    ObjectNode withoutChecksum = definition.deepCopy();
    withoutChecksum.remove("checksum");
    try {
      byte[] canonical =
          new JsonCanonicalizer(mapper.writeValueAsString(withoutChecksum)).getEncodedUTF8();
      String actual =
          "sha256:"
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
      require(actual.equals(definition.path("checksum").textValue()), "checksum mismatch");
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new IllegalArgumentException("invalid canonical definition", ex);
    }
  }

  static UUID id(JsonNode node, String field) {
    return UUID.fromString(node.path(field).textValue());
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }
}
