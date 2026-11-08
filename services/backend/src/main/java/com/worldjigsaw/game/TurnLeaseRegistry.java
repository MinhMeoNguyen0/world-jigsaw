package com.worldjigsaw.game;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Process-local lease and preview primitive; the future coordinator owns network handoff and rules.
 */
@Component
class TurnLeaseRegistry {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int MAX_TURNS = 100;
  private static final int MAX_GROUPS = 80;
  private static final int MAX_ACTIONS = 80;
  private static final long MAX_SAFE = 9_007_199_254_740_991L;
  private final Clock clock;
  private final Map<UUID, Turn> turns = new HashMap<>();
  private final Map<Key, UUID> activeBySubject = new HashMap<>();
  private final Map<Key, UUID> groupOwners = new HashMap<>();
  private final List<Cleared> clears = new ArrayList<>();
  private boolean clearOverflow;

  private record Key(UUID puzzle, UUID id) {}

  record Info(
      UUID puzzle, UUID subject, UUID turn, long generation, Instant expiresAt, Set<UUID> groups) {}

  record Cleared(UUID puzzle, UUID turn, long generation, String reason) {}

  record ClearBatch(List<Cleared> events, boolean resyncRequired) {}

  static class LeaseError extends RuntimeException {
    final String code;

    LeaseError(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  private static final class Turn {
    final UUID puzzle, subject, id;
    final Instant closesAt;
    final Set<UUID> groups = new HashSet<>();
    long generation;
    UUID confirming;
    Instant expiresAt;
    PreviewGeneration previews;
    final Map<UUID, PreviewGeneration.Action> actions = new LinkedHashMap<>();

    Turn(UUID puzzle, UUID subject, UUID id, Instant closesAt, Instant expiresAt) {
      this.puzzle = puzzle;
      this.subject = subject;
      this.id = id;
      this.closesAt = closesAt;
      this.expiresAt = expiresAt;
      previews = new PreviewGeneration(puzzle, id, 0);
    }
  }

  TurnLeaseRegistry(Clock clock) {
    this.clock = clock;
  }

  // In-process tests may use random IDs; a coordinator must use the durable supplier overload.
  synchronized Info open(UUID puzzle, UUID subject, Instant closesAt) {
    return open(puzzle, subject, closesAt, UUID::randomUUID);
  }

  synchronized Info open(
      UUID puzzle, UUID subject, Instant closesAt, java.util.function.Supplier<UUID> reserveId) {
    sweep();
    Instant now = clock.instant();
    if (!now.isBefore(closesAt)) throw error("PUZZLE_CLOSED", "Puzzle is closed");
    UUID existing = activeBySubject.get(new Key(puzzle, subject));
    if (existing != null) return info(turns.get(existing));
    if (turns.size() >= MAX_TURNS) throw error("INVALID_ACTION", "Turn capacity reached");
    UUID id = reserveId.get();
    if (id == null || turns.containsKey(id)) throw error("INVALID_ACTION", "Turn ID already used");
    Turn turn = new Turn(puzzle, subject, id, closesAt, expiry(now, closesAt));
    turns.put(id, turn);
    activeBySubject.put(new Key(puzzle, subject), id);
    return info(turn);
  }

  /** All group reservations are acquired together, or none are acquired. */
  synchronized Info acquire(UUID subject, UUID turnId, long generation, Set<UUID> groups) {
    Turn turn = active(subject, turnId, generation);
    if (groups.isEmpty()
        || groups.size() > MAX_GROUPS
        || groups.stream().anyMatch(java.util.Objects::isNull))
      throw error("INVALID_ACTION", "Invalid group set");
    Set<UUID> combined = new HashSet<>(turn.groups);
    combined.addAll(groups);
    if (combined.size() > MAX_GROUPS) throw error("INVALID_ACTION", "Group capacity reached");
    for (UUID id : groups) {
      UUID owner = groupOwners.get(new Key(turn.puzzle, id));
      if (owner != null && !owner.equals(turn.id)) throw error("PIECE_BUSY", "Group is busy");
    }
    for (UUID id : groups) {
      groupOwners.put(new Key(turn.puzzle, id), turn.id);
      turn.groups.add(id);
    }
    return info(turn);
  }

  /** Releases only newly acquired groups not referenced by any accepted action. */
  synchronized Info releaseUnused(UUID subject, UUID turnId, long generation, Set<UUID> groups) {
    Turn turn = active(subject, turnId, generation);
    if (!turn.groups.containsAll(groups)) throw error("INVALID_ACTION", "Group is not reserved");
    for (PreviewGeneration.Action action : turn.actions.values()) {
      for (PreviewGeneration.BaseRevision revision : action.baseRevisions()) {
        if (groups.contains(revision.groupId()))
          throw error("INVALID_ACTION", "Group is still in use");
      }
    }
    for (UUID id : groups) {
      turn.groups.remove(id);
      groupOwners.remove(new Key(turn.puzzle, id));
    }
    return info(turn);
  }

  /** The caller must first validate geometry, action history, revisions and quota atomically. */
  synchronized boolean publish(
      UUID subject, UUID turnId, long generation, long sequence, PreviewGeneration.Action action) {
    Turn turn = active(subject, turnId, generation);
    if (action.baseRevisions().isEmpty()
        || action.baseRevisions().stream().anyMatch(r -> !turn.groups.contains(r.groupId())))
      throw error("INVALID_ACTION", "Unreserved group");
    if (!turn.actions.containsKey(action.actionId()) && turn.actions.size() >= MAX_ACTIONS)
      throw error("INVALID_ACTION", "Action capacity reached");
    boolean accepted = turn.previews.publish(sequence, action, turn.expiresAt);
    if (accepted) turn.actions.put(action.actionId(), action);
    return accepted;
  }

  synchronized Info heartbeat(UUID subject, UUID turnId, long generation) {
    Turn turn = active(subject, turnId, generation);
    turn.expiresAt = expiry(clock.instant(), turn.closesAt);
    return info(turn);
  }

  /** Caller has already verified undo dependencies and supplied exactly its surviving actions. */
  synchronized Info fenceUndo(
      UUID subject, UUID turnId, long generation, List<PreviewGeneration.Action> survivors) {
    Turn turn = active(subject, turnId, generation);
    if (generation == MAX_SAFE || survivors.size() > MAX_ACTIONS)
      throw error("INVALID_ACTION", "Generation exhausted");
    Set<UUID> ids = new HashSet<>(), keptGroups = new HashSet<>();
    for (PreviewGeneration.Action action : survivors) {
      if (!ids.add(action.actionId())
          || !action.equals(turn.actions.get(action.actionId()))
          || action.baseRevisions().isEmpty())
        throw error("INVALID_ACTION", "Invalid surviving actions");
      for (var revision : action.baseRevisions()) {
        if (!turn.groups.contains(revision.groupId()))
          throw error("INVALID_ACTION", "Unreserved group");
        keptGroups.add(revision.groupId());
      }
    }
    turn.previews.clear();
    recordClear(new Cleared(turn.puzzle, turn.id, turn.generation, "undone"));
    for (UUID id : new HashSet<>(turn.groups))
      if (!keptGroups.contains(id)) {
        turn.groups.remove(id);
        groupOwners.remove(new Key(turn.puzzle, id));
      }
    turn.generation++;
    turn.previews = new PreviewGeneration(turn.puzzle, turn.id, turn.generation);
    turn.actions.clear();
    long sequence = 0;
    for (PreviewGeneration.Action action : survivors) {
      turn.previews.publish(sequence++, action, turn.expiresAt);
      turn.actions.put(action.actionId(), action);
    }
    return info(turn);
  }

  /**
   * Call only after admission under the database board lock, then retain until a definitive result.
   */
  synchronized Info pinConfirm(UUID subject, UUID turnId, long generation, UUID commandId) {
    Turn turn = active(subject, turnId, generation);
    if (commandId == null) throw error("INVALID_ACTION", "Missing command ID");
    turn.confirming = commandId;
    return info(turn);
  }

  synchronized UUID confirming(UUID subject, UUID turnId, long generation) {
    sweep();
    Turn turn = turns.get(turnId);
    if (turn == null || !turn.subject.equals(subject) || turn.generation != generation)
      throw error("TURN_CLOSED", "Turn is closed or fenced");
    return turn.confirming;
  }

  synchronized void finishConfirm(
      UUID subject, UUID turnId, long generation, UUID commandId, boolean confirmed) {
    sweep();
    Turn turn = turns.get(turnId);
    if (turn == null
        || !turn.subject.equals(subject)
        || turn.generation != generation
        || !commandId.equals(turn.confirming)) throw error("TURN_CLOSED", "Unknown confirmation");
    retire(turn, confirmed ? "confirmed" : "rejected");
  }

  synchronized boolean clear(UUID subject, UUID turnId, long generation, String reason) {
    Turn turn = active(subject, turnId, generation);
    if (!Set.of("canceled", "confirmed", "rejected").contains(reason))
      throw error("INVALID_ACTION", "Invalid clear reason");
    retire(turn, reason);
    return true;
  }

  /** Latest action frames in sequence order; expiry reflects a successful heartbeat. */
  synchronized List<PreviewGeneration.Preview> previews(UUID puzzle) {
    sweep();
    List<PreviewGeneration.Preview> result = new ArrayList<>();
    for (Turn turn : turns.values())
      if (turn.puzzle.equals(puzzle))
        for (var preview : turn.previews.snapshot()) {
          Instant displayedExpiry =
              turn.confirming == null ? turn.expiresAt : clock.instant().plus(LEASE);
          result.add(
              new PreviewGeneration.Preview(
                  preview.puzzleId(),
                  preview.turnId(),
                  preview.generation(),
                  preview.sequence(),
                  preview.action(),
                  displayedExpiry));
        }
    result.sort(
        java.util.Comparator.comparing(PreviewGeneration.Preview::turnId)
            .thenComparingLong(PreviewGeneration.Preview::sequence));
    return List.copyOf(result);
  }

  synchronized ClearBatch drainClears() {
    sweep();
    ClearBatch result = new ClearBatch(List.copyOf(clears), clearOverflow);
    clears.clear();
    clearOverflow = false;
    return result;
  }

  private void recordClear(Cleared clear) {
    if (clears.size() < 1024) clears.add(clear);
    else clearOverflow = true;
  }

  private Turn active(UUID subject, UUID id, long generation) {
    sweep();
    Turn turn = turns.get(id);
    if (turn == null || !turn.subject.equals(subject) || turn.generation != generation)
      throw error("TURN_CLOSED", "Turn is closed or fenced");
    if (turn.confirming != null) throw error("TURN_CLOSED", "Confirm is processing");
    return turn;
  }

  private void sweep() {
    Instant now = clock.instant();
    for (Turn turn : List.copyOf(turns.values())) {
      if (turn.confirming != null) continue;
      if (!now.isBefore(turn.closesAt)) retire(turn, "rollover");
      else if (!now.isBefore(turn.expiresAt)) retire(turn, "expired");
    }
  }

  private void retire(Turn turn, String reason) {
    turn.previews.clear();
    turns.remove(turn.id);
    activeBySubject.remove(new Key(turn.puzzle, turn.subject));
    for (UUID id : turn.groups) groupOwners.remove(new Key(turn.puzzle, id));
    recordClear(new Cleared(turn.puzzle, turn.id, turn.generation, reason));
  }

  private static Instant expiry(Instant now, Instant closesAt) {
    Instant expiry = now.plus(LEASE);
    return expiry.isBefore(closesAt) ? expiry : closesAt;
  }

  private static Info info(Turn t) {
    return new Info(t.puzzle, t.subject, t.id, t.generation, t.expiresAt, Set.copyOf(t.groups));
  }

  private static LeaseError error(String code, String message) {
    return new LeaseError(code, message);
  }
}
