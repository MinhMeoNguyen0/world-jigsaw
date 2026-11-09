package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

class GameShapeGeometryTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final GameShapeService shapes = new GameShapeService(null, null, null, null, null, null);

  @Test
  void allSliderExtremesKeepReciprocalContoursAndValidExistingBoard() throws Exception {
    for (String filename : new String[] {"surround-60.json", "hoi-an-balanced-60.json"}) {
      JsonNode fixture =
          mapper.readTree(Path.of("../../packages/generator/fixtures/" + filename).toFile());
      for (int depth : new int[] {100, 220})
        for (int width : new int[] {220, 360})
          for (int wave : new int[] {0, 60}) {
            ObjectNode definition = fixture.path("definition").deepCopy();
            shapes.reshape(definition, depth, width, wave);
            definition.put("checksum", checksum(definition));
            new BoardValidator().validate(definition, fixture.path("snapshot"));
            assertThat(definition.path("shape").path("tabDepthPermille").intValue())
                .isEqualTo(depth);
            assertThat(definition.path("shape").path("tabWidthPermille").intValue())
                .isEqualTo(width);
            assertThat(definition.path("shape").path("edgeWavePermille").intValue())
                .isEqualTo(wave);
            assertThat(LocalGeometry.hasForbiddenOverlap(definition, fixture.path("snapshot")))
                .isFalse();
          }
    }
  }

  @Test
  void unsupportedLegacyShapeIsRejectedWithoutChangingItsData() throws Exception {
    ObjectNode definition =
        (ObjectNode)
            mapper
                .readTree(Path.of("../../packages/generator/fixtures/surround-60.json").toFile())
                .path("definition")
                .deepCopy();
    ((ObjectNode) definition.path("shape")).put("generatorVersion", "legacy");
    JsonNode before = definition.deepCopy();
    assertThrows(HttpFailure.class, () -> shapes.reshape(definition, 100, 340, 60));
    assertThat(definition).isEqualTo(before);
  }

  private String checksum(ObjectNode definition) throws Exception {
    ObjectNode content = definition.deepCopy();
    content.remove("checksum");
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        new JsonCanonicalizer(mapper.writeValueAsString(content))
                            .getEncodedUTF8()));
  }
}
