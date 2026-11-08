package com.worldjigsaw.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Transient overlays for one already-authorized turn generation; never canonical board state. */
public final class PreviewGeneration {
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public record Point(long x, long y) {}

  public record Transform(Point translation, int rotation) {
    public Transform {
      Objects.requireNonNull(translation);
    }
  }

  public record BaseRevision(UUID groupId, long revision) {
    public BaseRevision {
      Objects.requireNonNull(groupId);
    }
  }

  /** The caller validates the action, including geometry and revisions, before publishing. */
  public record Action(
      UUID actionId,
      String kind,
      UUID targetId,
      List<UUID> affectedPieceIds,
      List<BaseRevision> baseRevisions,
      Transform intendedTransform) {
    public Action {
      Objects.requireNonNull(actionId);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(targetId);
      affectedPieceIds = List.copyOf(affectedPieceIds);
      baseRevisions = List.copyOf(baseRevisions);
      Objects.requireNonNull(intendedTransform);
    }
  }

  public record Preview(
      UUID puzzleId,
      UUID turnId,
      long generation,
      long sequence,
      Action action,
      Instant expiresAt) {}

  private final UUID puzzleId;
  private final UUID turnId;
  private final long generation;
  private final Map<UUID, Preview> latestByAction = new HashMap<>();
  private long lastSequence = -1;
  private boolean cleared;

  public PreviewGeneration(UUID puzzleId, UUID turnId, long generation) {
    this.puzzleId = Objects.requireNonNull(puzzleId);
    this.turnId = Objects.requireNonNull(turnId);
    if (generation < 0 || generation > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("generation outside JSON safe integer range");
    }
    this.generation = generation;
  }

  /**
   * Installs a validated action only when its sequence is newer for this generation. A rejected
   * frame leaves the overlay unchanged. Authorization, lease checks, expiration, and broadcasts are
   * the responsibility of the owner coordinating this object.
   */
  public synchronized boolean publish(long sequence, Action action, Instant expiresAt) {
    Objects.requireNonNull(action);
    Objects.requireNonNull(expiresAt);
    if (cleared || sequence < 0 || sequence > MAX_SAFE_INTEGER || sequence <= lastSequence) {
      return false;
    }
    lastSequence = sequence;
    latestByAction.put(
        action.actionId(), new Preview(puzzleId, turnId, generation, sequence, action, expiresAt));
    return true;
  }

  /** Returns every action's latest preview, ordered by sequence, for a subscription handoff. */
  public synchronized List<Preview> snapshot() {
    List<Preview> result = new ArrayList<>(latestByAction.values());
    result.sort(Comparator.comparingLong(Preview::sequence));
    return List.copyOf(result);
  }

  /** Permanently fences this generation; repeated clears have no effect. */
  public synchronized boolean clear() {
    if (cleared) {
      return false;
    }
    cleared = true;
    latestByAction.clear();
    return true;
  }
}
