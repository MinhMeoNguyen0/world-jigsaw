package com.worldjigsaw.game;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Private-local single writer guard; the game database is dedicated to this service. */
@Component
class GameWriterGuard {
  private static final long LOCK_KEY = 0x574a494753415645L;
  private final Connection connection;
  private final AtomicBoolean valid = new AtomicBoolean(true);

  GameWriterGuard(DataSource dataSource) throws SQLException {
    Connection candidate = dataSource.getConnection();
    try (PreparedStatement statement =
        candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      statement.setLong(1, LOCK_KEY);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !result.getBoolean(1))
          throw new IllegalStateException("Another game writer owns this database");
      }
      connection = candidate;
    } catch (SQLException | RuntimeException ex) {
      candidate.close();
      throw ex;
    }
  }

  synchronized void check() {
    if (!valid.get()) throw new IllegalStateException("Game writer lock is unavailable");
    try {
      if (!connection.isValid(2)) throw new SQLException("Writer connection is invalid");
    } catch (SQLException ex) {
      valid.set(false);
      try {
        connection.close();
      } catch (SQLException ignored) {
      }
      throw new IllegalStateException("Game writer lock is unavailable", ex);
    }
  }

  boolean healthy() {
    try {
      check();
      return true;
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  @PreDestroy
  synchronized void close() {
    if (valid.getAndSet(false)) {
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
        statement.setLong(1, LOCK_KEY);
        statement.execute();
      } catch (SQLException ignored) {
      }
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
    }
  }
}
