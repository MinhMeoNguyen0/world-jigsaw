package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class UnicodeConformanceTest {
  @Test
  void unicode151GraphemeBreakConformance() throws Exception {
    // Unicode Consortium GraphemeBreakTest-15.1.0, UAX #29 default cluster boundaries.
    try (var stream = getClass().getResourceAsStream("/GraphemeBreakTest-15.1.0.txt")) {
      String data = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      int cases = 0;
      for (String line : data.split("\\R")) {
        String spec = line.split("#", 2)[0].trim();
        if (spec.isEmpty()) continue;
        var expected = new ArrayList<Integer>();
        var text = new StringBuilder();
        for (String token : spec.split("\\s+")) {
          if (token.equals("÷")) expected.add(text.length());
          else if (!token.equals("×")) text.appendCodePoint(Integer.parseInt(token, 16));
        }
        var iterator = BreakIterator.getCharacterInstance(ULocale.ROOT);
        iterator.setText(text.toString());
        var actual = new ArrayList<Integer>();
        for (int boundary = iterator.first();
            boundary != BreakIterator.DONE;
            boundary = iterator.next()) actual.add(boundary);
        assertEquals(expected, actual, spec);
        cases++;
      }
      assertTrue(cases > 1000);
    }
  }

  @Test
  void s3ProposedUnicodeVectors() throws Exception {
    try (var stream = getClass().getResourceAsStream("/chat-wire-proposal.json")) {
      var fixture = new ObjectMapper().readTree(stream);
      for (var vector : fixture.path("unicodeFixtures")) {
        String result = ChatServer.normalize(vector.path("input").asText());
        if (vector.path("valid").asBoolean())
          assertEquals(vector.path("normalized").asText(), result, vector.path("name").asText());
        else assertNull(result, vector.path("name").asText());
      }
    }
  }
}
