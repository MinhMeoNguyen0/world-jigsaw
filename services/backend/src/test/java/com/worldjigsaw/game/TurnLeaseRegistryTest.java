package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TurnLeaseRegistryTest {
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-07T23:59:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };
  private final TurnLeaseRegistry registry = new TurnLeaseRegistry(clock);
  private final UUID puzzle = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
  private final Instant close = Instant.parse("2026-11-08T00:00:00Z");

  private PreviewGeneration.Action action(UUID id, UUID group, int x) {
    return new PreviewGeneration.Action(
        id,
        "piece",
        group,
        List.of(UUID.randomUUID()),
        List.of(new PreviewGeneration.BaseRevision(group, 0)),
        new PreviewGeneration.Transform(new PreviewGeneration.Point(x, 0), 0));
  }

  @Test
  void competingPlayersAcquireAllOrNothingAndExpiredOwnerIsFenced() {
    var first = registry.open(puzzle, a, close);
    var second = registry.open(puzzle, b, close);
    UUID one = UUID.randomUUID(), two = UUID.randomUUID();
    registry.acquire(a, first.turn(), 0, Set.of(one));
    assertThatThrownBy(() -> registry.acquire(b, second.turn(), 0, Set.of(one, two)))
        .isInstanceOf(TurnLeaseRegistry.LeaseError.class)
        .hasMessageContaining("busy");
    assertThat(registry.acquire(b, second.turn(), 0, Set.of(two)).groups()).containsExactly(two);
    now.set(Instant.parse("2026-11-07T23:59:31Z"));
    var replacement = registry.open(puzzle, b, close);
    assertThat(replacement.turn()).isNotEqualTo(second.turn());
    assertThat(registry.acquire(b, replacement.turn(), 0, Set.of(one)).groups())
        .containsExactly(one);
    assertThatThrownBy(
            () -> registry.publish(a, first.turn(), 0, 1, action(UUID.randomUUID(), one, 1)))
        .isInstanceOf(TurnLeaseRegistry.LeaseError.class);
    assertThat(registry.drainClears().events())
        .extracting(TurnLeaseRegistry.Cleared::reason)
        .containsExactlyInAnyOrder("expired", "expired");
  }

  @Test
  void undoFencesOldFramesAndReconnectGetsEveryLatestSurvivor() {
    var turn = registry.open(puzzle, a, close);
    UUID one = UUID.randomUUID(), two = UUID.randomUUID();
    registry.acquire(a, turn.turn(), 0, Set.of(one, two));
    UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
    var first = action(firstId, one, 1);
    var second = action(secondId, two, 2);
    assertThat(registry.publish(a, turn.turn(), 0, 1, first)).isTrue();
    assertThat(registry.publish(a, turn.turn(), 0, 2, second)).isTrue();
    var adjusted =
        new PreviewGeneration.Action(
            firstId,
            first.kind(),
            first.targetId(),
            first.affectedPieceIds(),
            first.baseRevisions(),
            new PreviewGeneration.Transform(new PreviewGeneration.Point(3, 0), 0));
    assertThat(registry.publish(a, turn.turn(), 0, 3, adjusted)).isTrue();
    assertThat(registry.previews(puzzle))
        .extracting(p -> p.action().actionId())
        .containsExactly(secondId, firstId);
    var fenced = registry.fenceUndo(a, turn.turn(), 0, List.of(second));
    assertThat(fenced.generation()).isEqualTo(1);
    assertThat(fenced.groups()).containsExactly(two);
    assertThat(registry.drainClears().events())
        .extracting(TurnLeaseRegistry.Cleared::reason)
        .containsExactly("undone");
    assertThat(registry.previews(puzzle))
        .extracting(p -> p.action().actionId())
        .containsExactly(secondId);
    assertThatThrownBy(() -> registry.publish(a, turn.turn(), 0, 4, adjusted))
        .isInstanceOf(TurnLeaseRegistry.LeaseError.class);
    assertThat(registry.previews(puzzle)).hasSize(1);
  }

  @Test
  void processingConfirmRemainsPinnedAcrossExpiryAndUtcRollover() {
    var turn = registry.open(puzzle, a, close);
    UUID group = UUID.randomUUID(), command = UUID.randomUUID();
    registry.acquire(a, turn.turn(), 0, Set.of(group));
    registry.publish(a, turn.turn(), 0, 1, action(UUID.randomUUID(), group, 1));
    now.set(Instant.parse("2026-11-07T23:59:20Z"));
    assertThat(registry.heartbeat(a, turn.turn(), 0).expiresAt())
        .isEqualTo(Instant.parse("2026-11-07T23:59:50Z"));
    registry.pinConfirm(a, turn.turn(), 0, command, now.get());
    now.set(Instant.parse("2026-11-08T00:01:00Z"));
    assertThat(registry.previews(puzzle)).hasSize(1);
    assertThat(registry.confirming(a, turn.turn(), 0)).isEqualTo(command);
    assertThatThrownBy(() -> registry.clear(a, turn.turn(), 0, "canceled"))
        .isInstanceOf(TurnLeaseRegistry.LeaseError.class);
    registry.finishConfirm(a, turn.turn(), 0, command, true);
    assertThat(registry.previews(puzzle)).isEmpty();
    assertThat(registry.drainClears().events())
        .extracting(TurnLeaseRegistry.Cleared::reason)
        .containsExactly("confirmed");
  }

  @Test
  void unpinRestoresLiveExpiryAndRetiresExpiredTurns() {
    var turn = registry.open(puzzle, a, close);
    UUID group = UUID.randomUUID(), command = UUID.randomUUID();
    registry.acquire(a, turn.turn(), 0, Set.of(group));
    registry.publish(a, turn.turn(), 0, 1, action(UUID.randomUUID(), group, 1));
    registry.pinConfirm(a, turn.turn(), 0, command, now.get());
    var pinned = registry.previews(puzzle).getFirst();
    var restored = registry.unpinConfirm(a, turn.turn(), 0, command).getFirst();
    assertThat(restored.sequence()).isGreaterThan(pinned.sequence());
    assertThat(restored.expiresAt()).isEqualTo(turn.expiresAt());
    assertThat(registry.info(a, turn.turn(), 0).groups()).containsExactly(group);
    registry.pinConfirm(a, turn.turn(), 0, command, now.get());
    now.set(turn.expiresAt());
    assertThat(registry.unpinConfirm(a, turn.turn(), 0, command)).isEmpty();
    assertThat(registry.activeTurns()).doesNotContain(turn.turn());
    assertThat(registry.drainClears().events())
        .extracting(TurnLeaseRegistry.Cleared::reason).containsExactly("expired");
  }

  @Test
  void repeatedPinRequiresFreshLiveAdmission() {
    var turn = registry.open(puzzle, a, close);
    UUID command = UUID.randomUUID();
    registry.pinConfirm(a, turn.turn(), 0, command, now.get());
    now.set(turn.expiresAt());
    assertThatThrownBy(() -> registry.pinConfirm(a, turn.turn(), 0, command, now.get()))
        .isInstanceOf(TurnLeaseRegistry.LeaseError.class);
    assertThat(registry.confirming(a, turn.turn(), 0)).isEqualTo(command);
    assertThat(registry.unpinConfirm(a, turn.turn(), 0, command)).isEmpty();
  }

  @Test
  void undeliveredClearOverflowRequiresResynchronization() {
    for (int i = 0; i < 1025; i++) {
      var turn = registry.open(puzzle, a, close);
      registry.clear(a, turn.turn(), 0, "canceled");
    }
    var batch = registry.drainClears();
    assertThat(batch.events()).hasSize(1024);
    assertThat(batch.resyncRequired()).isTrue();
    assertThat(registry.drainClears().resyncRequired()).isFalse();
  }

  @Test
  void rejectedEditCanReleaseOnlyReservationsNotUsedByExistingActions() {
    var first = registry.open(puzzle, a, close);
    var second = registry.open(puzzle, b, close);
    UUID used = UUID.randomUUID(), provisional = UUID.randomUUID();
    registry.acquire(a, first.turn(), 0, Set.of(used, provisional));
    registry.publish(a, first.turn(), 0, 1, action(UUID.randomUUID(), used, 1));
    assertThatThrownBy(() -> registry.releaseUnused(a, first.turn(), 0, Set.of(used)))
        .isInstanceOf(TurnLeaseRegistry.LeaseError.class);
    registry.releaseUnused(a, first.turn(), 0, Set.of(provisional));
    assertThat(registry.acquire(b, second.turn(), 0, Set.of(provisional)).groups())
        .containsExactly(provisional);
    assertThat(registry.previews(puzzle)).hasSize(1);
  }

  @Test
  void sampledPreCloseAdmissionCanPinEvenIfClockAdvancesBeforeCallback() {
    now.set(Instant.parse("2026-11-07T23:59:35Z"));
    var turn = registry.open(puzzle, a, close);
    Instant admitted = Instant.parse("2026-11-07T23:59:59Z");
    now.set(Instant.parse("2026-11-08T00:00:00Z"));
    UUID command = UUID.randomUUID();
    registry.pinConfirm(a, turn.turn(), 0, command, admitted);
    assertThat(registry.confirming(a, turn.turn(), 0)).isEqualTo(command);
    registry.finishConfirm(a, turn.turn(), 0, command, true);
    assertThat(registry.drainClears().events())
        .extracting(TurnLeaseRegistry.Cleared::reason)
        .containsExactly("confirmed");
  }
}
