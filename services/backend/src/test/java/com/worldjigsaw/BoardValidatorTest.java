package com.worldjigsaw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.worldjigsaw.game.BoardValidator;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

class BoardValidatorTest {
  @Test
  void acceptsEquivalentIntegerRepresentationsAndRejectsDifferentValues() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode fixture =
        mapper.readTree(
            Path.of("../../packages/contracts/v1/fixtures/board-and-batch.json").toFile());
    ObjectNode definition = fixture.path("definition").deepCopy();
    ObjectNode snapshot = fixture.path("snapshot").deepCopy();
    BoardValidator validator = new BoardValidator();

    validator.validate(definition, snapshot);
    snapshot.put("definitionVersion", 1.0);
    validator.validate(definition, snapshot);
    snapshot.put("definitionVersion", 1);
    definition.put("definitionVersion", 1.0);
    validator.validate(definition, snapshot);

    for (JsonNode piece : definition.path("pieces")) {
      for (JsonNode edge : piece.path("edges")) {
        for (JsonNode point :
            new JsonNode[] {edge.path("start"), edge.path("segments").get(0).path("to")}) {
          for (String axis : new String[] {"x", "y"}) {
            ObjectNode object = (ObjectNode) point;
            int value = object.path(axis).intValue();
            object.put(axis, (double) value);
            validator.validate(definition, snapshot);
            object.put(axis, value);
          }
        }
      }
    }

    snapshot.put("definitionVersion", 2.0);
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> validator.validate(definition, snapshot)))
        .hasMessage("version mismatch");
    snapshot.put("definitionVersion", 1);
    ((ObjectNode)
            definition.path("pieces").get(0).path("edges").get(0).path("segments").get(0).path("to"))
        .put("x", 999.0);
    ObjectNode withoutChecksum = definition.deepCopy();
    withoutChecksum.remove("checksum");
    byte[] canonical =
        new JsonCanonicalizer(mapper.writeValueAsString(withoutChecksum)).getEncodedUTF8();
    definition.put(
        "checksum",
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)));
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> validator.validate(definition, snapshot)))
        .hasMessage("piece contour is not closed");
  }
}
