package com.worldjigsaw.chat;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

record ChatRequest(String messageId, String text) {
  static ChatRequest read(InputStream body, int maxGraphemes) throws IOException {
    return new Decoder(body, maxGraphemes).read();
  }

  private static final class Decoder {
    private final Reader input;
    private final int maxGraphemes;

    Decoder(InputStream body, int maxGraphemes) {
      input = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8.newDecoder()));
      this.maxGraphemes = maxGraphemes;
    }

    ChatRequest read() throws IOException {
      expect('{');
      Set<String> seen = new HashSet<>();
      String id = null;
      String text = null;
      boolean schema = false;
      do {
        String name = string(13);
        if (!Set.of("schemaVersion", "messageId", "text").contains(name) || !seen.add(name))
          throw invalid();
        expect(':');
        switch (name) {
          case "schemaVersion" -> {
            expect('1');
            schema = true;
          }
          case "messageId" -> {
            id = string(36);
            try {
              if (!UUID.fromString(id).toString().equals(id)) throw invalid();
            } catch (IllegalArgumentException ex) {
              throw invalid();
            }
          }
          case "text" -> text = text();
          default -> throw invalid();
        }
        int separator = next();
        if (separator == '}') break;
        if (separator != ',') throw invalid();
      } while (true);
      if (!schema || id == null || text == null || next() != -1) throw invalid();
      return new ChatRequest(id, text);
    }

    private String string(int maxLength) throws IOException {
      expect('"');
      StringBuilder value = new StringBuilder();
      int character;
      while ((character = character()) != -1) {
        if (value.length() == maxLength) throw invalid();
        value.append((char) character);
      }
      return value.toString();
    }

    private String text() throws IOException {
      expect('"');
      StringBuilder value = new StringBuilder();
      StringBuilder spaces = new StringBuilder();
      int spaceCount = 0;
      GraphemeCounter counter = new GraphemeCounter();
      int unit;
      while ((unit = character()) != -1) {
        int cp = unit;
        if (Character.isHighSurrogate((char) unit)) {
          int low = character();
          if (low == -1 || !Character.isLowSurrogate((char) low)) throw invalid();
          cp = Character.toCodePoint((char) unit, (char) low);
        } else if (Character.isLowSurrogate((char) unit)) {
          throw invalid();
        }
        if (UCharacter.getType(cp) == UCharacter.CONTROL) throw invalid();
        if (UCharacter.hasBinaryProperty(cp, UProperty.WHITE_SPACE)) {
          if (!value.isEmpty() && spaceCount <= maxGraphemes) {
            spaceCount++;
            if (spaceCount <= maxGraphemes) spaces.appendCodePoint(cp);
          }
          continue;
        }
        if (spaceCount > maxGraphemes) throw invalid();
        for (int offset = 0; offset < spaces.length(); ) {
          int pending = spaces.codePointAt(offset);
          if (counter.add(pending) > maxGraphemes) throw invalid();
          offset += Character.charCount(pending);
        }
        if (counter.add(cp) > maxGraphemes) throw invalid();
        value.append(spaces);
        spaces.setLength(0);
        spaceCount = 0;
        value.appendCodePoint(cp);
      }
      String normalized = ChatServer.normalize(value.toString(), maxGraphemes);
      if (normalized == null) throw invalid();
      return normalized;
    }

    private int character() throws IOException {
      int ch = input.read();
      if (ch == '"') return -1;
      if (ch < 0 || ch < 0x20) throw invalid();
      if (ch != '\\') return ch;
      return switch (input.read()) {
        case '"' -> '"';
        case '\\' -> '\\';
        case '/' -> '/';
        case 'b' -> '\b';
        case 'f' -> '\f';
        case 'n' -> '\n';
        case 'r' -> '\r';
        case 't' -> '\t';
        case 'u' -> hex();
        default -> throw invalid();
      };
    }

    private int hex() throws IOException {
      int value = 0;
      for (int i = 0; i < 4; i++) {
        int ch = input.read();
        int digit =
            ch >= '0' && ch <= '9'
                ? ch - '0'
                : ch >= 'a' && ch <= 'f'
                    ? ch - 'a' + 10
                    : ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
        if (digit < 0) throw invalid();
        value = value * 16 + digit;
      }
      return value;
    }

    private void expect(int expected) throws IOException {
      if (next() != expected) throw invalid();
    }

    private int next() throws IOException {
      int ch;
      do {
        ch = input.read();
      } while (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n');
      return ch;
    }

    private IOException invalid() {
      return new IOException("Invalid chat request");
    }
  }
}
