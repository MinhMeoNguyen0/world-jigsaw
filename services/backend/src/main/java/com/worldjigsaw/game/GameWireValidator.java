package com.worldjigsaw.game;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Closed v1 shape and identifier validation, before any authenticated command is processed. */
@Component
class GameWireValidator {
  private static final Set<String> TYPES =
      Set.of(
          "BeginTurn",
          "Confirm",
          "ConfirmV2",
          "Cancel",
          "UndoV2",
          "ShapeChange",
          "Subscribe",
          "SubscribeV2",
          "CursorUpdate",
          "PreviewSubmit",
          "Heartbeat");
  private static final Set<String> UNIQUE_UUID_ARRAYS =
      Set.of("pieceIds", "affectedPieceIds", "removedGroupIds");
  private final ObjectMapper mapper =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final Map<String, JsonSchema> schemas = new HashMap<>();

  GameWireValidator() throws IOException {
    JsonNode original;
    try (var input = getClass().getResourceAsStream("/contracts/v1/schema.json")) {
      original = mapper.readTree(input);
    }
    for (String type : TYPES) {
      ObjectNode schema = original.deepCopy();
      schema.remove("oneOf");
      schema.put("$ref", "#/$defs/" + type);
      schemas.put(
          type, JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema));
    }
  }

  JsonNode parse(String type, String raw) {
    if (!schemas.containsKey(type) || raw == null || raw.length() > 65536)
      throw new IllegalArgumentException("Invalid request");
    try {
      JsonNode value = mapper.readTree(raw);
      if (value == null
          || !schemas.get(type).validate(value).isEmpty()
          || !uniqueIdentifiers(value)) {
        throw new IllegalArgumentException("Invalid request");
      }
      return value;
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid request", ex);
    }
  }

  private static boolean uniqueIdentifiers(JsonNode value) {
    if (value.isArray()) {
      for (JsonNode item : value) if (!uniqueIdentifiers(item)) return false;
    } else if (value.isObject()) {
      var fields = value.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        JsonNode item = field.getValue();
        if (UNIQUE_UUID_ARRAYS.contains(field.getKey())) {
          Set<String> seen = new HashSet<>();
          for (JsonNode id : item)
            if (!seen.add(id.textValue().toLowerCase(Locale.ROOT))) return false;
        }
        if ("baseRevisions".equals(field.getKey())) {
          Set<String> seen = new HashSet<>();
          for (JsonNode revision : item)
            if (!seen.add(revision.path("groupId").textValue().toLowerCase(Locale.ROOT)))
              return false;
        }
        if ("actions".equals(field.getKey())) {
          if (item.size() > 80) return false;
          Set<String> seen = new HashSet<>();
          for (JsonNode action : item)
            if (!seen.add(action.path("actionId").textValue().toLowerCase(Locale.ROOT)))
              return false;
        }
        if (!uniqueIdentifiers(item)) return false;
      }
    }
    return true;
  }
}
