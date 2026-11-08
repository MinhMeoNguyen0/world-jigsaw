package com.worldjigsaw.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class ChatSettingsTest {
  @Test
  void loadsRoomSettingsFromSeparateDatabaseWithoutMessageStorage() throws Exception {
    String url = "jdbc:h2:mem:chat_settings;DB_CLOSE_DELAY=-1";
    assertEquals(new ChatSettings(40, "15.1"), ChatSettings.load(url, "sa", ""));
    assertEquals(new ChatSettings(40, "15.1"), ChatSettings.load(url, "sa", ""));
    try (var db = DriverManager.getConnection(url, "sa", "");
        var statement = db.createStatement()) {
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM chat_settings")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
      assertThrows(
          java.sql.SQLException.class,
          () -> statement.executeUpdate("UPDATE chat_settings SET max_graphemes = 41"));
    }
  }

  @Test
  void unavailableDatabasePreventsSettingsLoading() {
    assertThrows(
        java.sql.SQLException.class,
        () -> ChatSettings.load("jdbc:h2:mem:missing;IFEXISTS=TRUE", "sa", ""));
  }
}
