package com.worldjigsaw.game;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Private-local single writer guard; the game database is dedicated to this service. */
@Component
class GameWriterGuard {
  private static final long LOCK_KEY = 0x574a494753415645L;
  private static final long DRAIN_KEY = 0x574a494744524149L;
  private static FileChannel hostChannel;
  private static FileLock hostLock;
  private static Path hostPath;
  private static final Set<String> usedDatabases = new HashSet<>();
  private final Connection connection;
  private final String database;
  private final AtomicBoolean valid = new AtomicBoolean(true);
  private final List<Object> operationBoundaries = new ArrayList<>();
  private boolean closing;

  GameWriterGuard(DataSource dataSource) throws SQLException {
    Connection candidate = dataSource.getConnection();
    String reserved = null;
    boolean registered = false;
    try {
      synchronized (GameWriterGuard.class) {
        acquireHostLock();
        reserved = candidate.getMetaData().getURL();
        if (!usedDatabases.add(reserved))
          throw new IllegalStateException("Another game writer owns this database");
        registered = true;
      }
      try (PreparedStatement statement =
          candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
        statement.setLong(1, LOCK_KEY);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next() || !result.getBoolean(1))
            throw new IllegalStateException("Another game writer owns this database");
        }
      }
      candidate.setAutoCommit(false);
      try (PreparedStatement barrier =
          candidate.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
        barrier.setLong(1, DRAIN_KEY);
        barrier.setQueryTimeout(30);
        barrier.execute();
      }
      candidate.commit();
      candidate.setAutoCommit(true);
      database = reserved;
      connection = candidate;
    } catch (SQLException | RuntimeException ex) {
      candidate.close();
      if (registered)
        synchronized (GameWriterGuard.class) {
          usedDatabases.remove(reserved);
        }
      throw ex;
    }
  }

  private static void acquireHostLock() {
    Path path = Path.of(".world-jigsaw-writer.lock").toAbsolutePath().normalize();
    if (hostLock != null) {
      if (!path.equals(hostPath) || !hostLock.isValid())
        throw new IllegalStateException("Local game writer lock is unavailable");
      return;
    }
    try {
      FileChannel channel =
          FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      try {
        FileLock lock = channel.tryLock();
        if (lock == null) throw new IllegalStateException("Another local game writer is running");
        hostChannel = channel;
        hostLock = lock;
        hostPath = path;
      } catch (RuntimeException | IOException failure) {
        channel.close();
        throw failure;
      }
    } catch (IOException | OverlappingFileLockException failure) {
      throw new IllegalStateException("Local game writer lock is unavailable", failure);
    }
  }

  void transactionBarrier(JdbcTemplate jdbc) {
    jdbc.queryForObject("SELECT pg_advisory_xact_lock_shared(?)", Object.class, DRAIN_KEY);
    check();
  }

  synchronized void attach(Object boundary) {
    if (closing || !valid.get()) throw new IllegalStateException("Game writer lock is unavailable");
    operationBoundaries.add(boundary);
  }

  synchronized void check() {
    if (closing || !valid.get()) throw new IllegalStateException("Game writer lock is unavailable");
    try {
      if (hostLock == null || !hostLock.isValid() || !hostChannel.isOpen())
        throw new SQLException("Local writer lock is invalid");
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
  void close() {
    List<Object> boundaries;
    synchronized (this) {
      if (closing) return;
      closing = true;
      boundaries = List.copyOf(operationBoundaries);
    }
    closeAfterOperations(boundaries, 0);
  }

  private void closeAfterOperations(List<Object> boundaries, int index) {
    if (index < boundaries.size()) {
      synchronized (boundaries.get(index)) {
        closeAfterOperations(boundaries, index + 1);
      }
      return;
    }
    synchronized (this) {
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
    synchronized (GameWriterGuard.class) {
      usedDatabases.remove(database);
    }
  }
}
