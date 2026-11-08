package com.worldjigsaw.chat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

record ChatSettings(int maxGraphemes, String unicodeVersion) {
  static ChatSettings fromEnvironment(Map<String, String> env) throws SQLException {
    return load(
        env.getOrDefault("CHAT_DATABASE_URL", "jdbc:postgresql://localhost:5433/worldjigsaw_chat"),
        env.getOrDefault("CHAT_DATABASE_USER", "worldjigsaw_chat"),
        env.getOrDefault("CHAT_DATABASE_PASSWORD", "worldjigsaw_chat"));
  }

  static ChatSettings load(String url, String user, String password) throws SQLException {
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS chat_settings (id INTEGER PRIMARY KEY CHECK (id = 1), "
              + "max_graphemes INTEGER NOT NULL CHECK (max_graphemes = 40), "
              + "unicode_version VARCHAR(10) NOT NULL CHECK (unicode_version = '15.1'))");
      try {
        statement.executeUpdate(
            "INSERT INTO chat_settings (id, max_graphemes, unicode_version) "
                + "SELECT 1, 40, '15.1' WHERE NOT EXISTS (SELECT 1 FROM chat_settings WHERE id = 1)");
      } catch (SQLException ex) {
        if (!"23505".equals(ex.getSQLState())) throw ex;
      }
      try (var result =
          statement.executeQuery(
              "SELECT max_graphemes, unicode_version FROM chat_settings WHERE id = 1")) {
        if (!result.next()) throw new SQLException("Missing chat settings");
        return new ChatSettings(result.getInt(1), result.getString(2));
      }
    }
  }
}
