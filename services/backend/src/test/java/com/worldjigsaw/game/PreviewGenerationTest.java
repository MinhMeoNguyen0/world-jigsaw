package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.worldjigsaw.game.PreviewGeneration.Action;
import com.worldjigsaw.game.PreviewGeneration.BaseRevision;
import com.worldjigsaw.game.PreviewGeneration.Point;
import com.worldjigsaw.game.PreviewGeneration.Transform;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PreviewGenerationTest {
  private static final UUID PUZZLE = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID TURN = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final UUID A = UUID.fromString("30000000-0000-4000-8000-000000000001");
  private static final UUID B = UUID.fromString("30000000-0000-4000-8000-000000000002");
  private static final Instant EXPIRY = Instant.parse("2026-11-08T10:00:30Z");

  private static Action action(UUID id, long x) {
    return new Action(
        id,
        "piece",
        id,
        List.of(id),
        List.of(new BaseRevision(id, 0)),
        new Transform(new Point(x, 0), 0));
  }

  @Test
  void reconnectSeesLatestPreviewForEveryActionInSequenceOrder() {
    PreviewGeneration overlays = new PreviewGeneration(PUZZLE, TURN, 3);
    assertThat(overlays.publish(1, action(A, 10), EXPIRY)).isTrue();
    assertThat(overlays.publish(2, action(B, 20), EXPIRY)).isTrue();
    assertThat(overlays.publish(3, action(A, 30), EXPIRY)).isTrue();

    assertThat(overlays.snapshot()).extracting(p -> p.action().actionId()).containsExactly(B, A);
    assertThat(overlays.snapshot().get(1).action().intendedTransform().translation().x())
        .isEqualTo(30);
    assertThat(overlays.snapshot().get(0).expiresAt()).isEqualTo(EXPIRY);
  }

  @Test
  void reorderedAndDuplicateFramesCannotReplaceNewerState() {
    PreviewGeneration overlays = new PreviewGeneration(PUZZLE, TURN, 0);
    assertThat(overlays.publish(5, action(A, 50), EXPIRY)).isTrue();
    assertThat(overlays.publish(4, action(B, 40), EXPIRY)).isFalse();
    assertThat(overlays.publish(5, action(A, 999), EXPIRY)).isFalse();
    assertThat(overlays.snapshot()).hasSize(1);
    assertThat(overlays.snapshot().getFirst().action().intendedTransform().translation().x())
        .isEqualTo(50);
  }

  @Test
  void cancellationFencesLateFramesWithoutChangingOtherGenerations() {
    PreviewGeneration old = new PreviewGeneration(PUZZLE, TURN, 1);
    PreviewGeneration replacement = new PreviewGeneration(PUZZLE, TURN, 2);
    old.publish(1, action(A, 10), EXPIRY);
    assertThat(old.clear()).isTrue();
    assertThat(old.clear()).isFalse();
    assertThat(old.publish(2, action(A, 20), EXPIRY)).isFalse();
    assertThat(old.snapshot()).isEmpty();
    assertThat(replacement.publish(0, action(A, 30), EXPIRY)).isTrue();
    assertThat(replacement.snapshot().getFirst().generation()).isEqualTo(2);
  }

  @Test
  void actionAndSnapshotCannotBeMutatedThroughCallerCollections() {
    List<UUID> pieces = new ArrayList<>(List.of(A));
    List<BaseRevision> revisions = new ArrayList<>(List.of(new BaseRevision(A, 0)));
    Action action = new Action(A, "piece", A, pieces, revisions, new Transform(new Point(0, 0), 0));
    PreviewGeneration overlays = new PreviewGeneration(PUZZLE, TURN, 0);
    overlays.publish(0, action, EXPIRY);
    pieces.add(B);
    revisions.clear();
    assertThat(overlays.snapshot().getFirst().action().affectedPieceIds()).containsExactly(A);
    assertThat(overlays.snapshot().getFirst().action().baseRevisions()).hasSize(1);
    assertThatThrownBy(() -> overlays.snapshot().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void wireSequenceAndGenerationMustFitJsonSafeIntegerRange() {
    long maxSafe = 9_007_199_254_740_991L;
    assertThatThrownBy(() -> new PreviewGeneration(PUZZLE, TURN, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PreviewGeneration(PUZZLE, TURN, maxSafe + 1))
        .isInstanceOf(IllegalArgumentException.class);
    PreviewGeneration overlays = new PreviewGeneration(PUZZLE, TURN, maxSafe);
    assertThat(overlays.publish(-1, action(A, 1), EXPIRY)).isFalse();
    assertThat(overlays.publish(maxSafe + 1, action(A, 1), EXPIRY)).isFalse();
    assertThat(overlays.publish(maxSafe, action(A, 2), EXPIRY)).isTrue();
    assertThat(overlays.snapshot().getFirst().sequence()).isEqualTo(maxSafe);
  }

  @Test
  void concurrentClearPermanentlyFencesTheGeneration() throws Exception {
    PreviewGeneration overlays = new PreviewGeneration(PUZZLE, TURN, 0);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var writer =
          executor.submit(() -> {
            start.await();
            for (int i = 0; i < 1000; i++) {
              overlays.publish(i, action(A, i), EXPIRY);
            }
            return null;
          });
      var cancel =
          executor.submit(() -> {
            start.await();
            return overlays.clear();
          });
      start.countDown();
      assertThat(cancel.get()).isTrue();
      writer.get();
    }
    assertThat(overlays.snapshot()).isEmpty();
    assertThat(overlays.publish(1001, action(A, 1001), EXPIRY)).isFalse();
  }
}
