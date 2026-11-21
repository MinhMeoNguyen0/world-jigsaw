package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Loads the bundled contract locally and selects individual draft-7 definitions. */
final class ContractSchemas {
  private static final String ID = "https://world-jigsaw.local/contracts/v1/schema.json";
  private static final ObjectMapper APPLICATION_JSON = new ObjectMapper();
  private final SchemaRegistry registry;

  ContractSchemas() throws IOException {
    String source;
    try (var input = ContractSchemas.class.getResourceAsStream("/contracts/v1/schema.json")) {
      if (input == null) throw new IOException("Missing bundled contract schema");
      source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_7, builder -> builder.schemas(Map.of(ID, source)));
  }

  Schema definition(String type) {
    Schema schema = registry.getSchema(SchemaLocation.of(ID + "#/$defs/" + type));
    schema.initializeValidators();
    return schema;
  }

  static boolean matches(Schema schema, JsonNode value) {
    // JSON text would quote non-finite doubles and let a number satisfy a string schema.
    Object data = APPLICATION_JSON.convertValue(value, Object.class);
    return schema.validate(JsonMapper.shared().valueToTree(data)).isEmpty();
  }
}
