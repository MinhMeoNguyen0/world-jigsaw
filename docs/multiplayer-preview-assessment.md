# Multiplayer preview assessment

## Before and after

```mermaid
sequenceDiagram
    participant A as Browser A
    participant W as Game WebSocket
    participant G as Game coordinator and database
    participant B as Browser B
    Note over A,B: Before: every rejected preview triggered a reconnect
    A->>G: Begin turn (HTTP)
    G-->>A: Turn; identity subscriptions invalidated
    A->>W: Preview submission
    W->>G: Validate candidate and reservations
    G-->>W: Collision rejection; preceding draft unchanged
    W-->>A: preview.rejected
    A->>A: Clear all previews and resubscribe (visible flash)
    Note over A,B: After: a known collision preserves the acknowledged draft
    A->>W: cursor.update (best effort, puzzle scoped)
    W-->>B: cursor.presence (ephemeral)
    A->>W: Preview submission
    W->>G: Validate candidate and reservations
    alt Valid
        G-->>A: Broadcast preview and correlated acceptance
        G-->>B: Temporary preview
    else Forbidden overlap
        G-->>A: Correlated rejection; draft unchanged
        A->>A: Keep connection and accepted draft; show invalid placement on release
    else Unknown, stale, or other failure
        A->>A: Resynchronize before editing
    end
    A->>G: Confirm exact acknowledged actions and revision (HTTP)
    G->>G: Atomically commit board, quota and command result
    G-->>A: Result or recoverable command ID
    G-->>B: Board event and preview clear
```

A drag begins locally, while each server-accepted preview is an ephemeral overlay. The server checks
workspace, collision, leases, revisions and quota; only Confirm changes the durable board and charges
moves. Undo fences the old generation and re-emits survivors; cancel reveals the current confirmed
board. A socket send or another player's broadcast is not an acceptance receipt. When a receipt is
unknown, or a rejection is not the documented placement cases whose server paths leave the draft intact,
resynchronization remains necessary. Confirm retries retain their command ID and payload; a lost
reply is not proof of success. Those paths should not be simplified by treating all errors as safe
or all previews as saved.

The first Begin invalidates the same identity's subscriptions so other tabs receive the new turn.
That causes a reconnect even for the tab that opened it. In one local trace this took approximately
175 ms between the first observed `connecting` and `ready` DOM states; the local drag ghost remained
visible. This is a coordination cost, not evidence of a general latency distribution. Removing the
invalidation without a replacement would leave same-session tabs with stale turn state. Other known
costs include a serialized preview receipt and a revision-correlated summary fetch. Measurements are
needed before introducing a wider protocol redesign.

Cursor presence is separate from turns and persistence. A subscribed client opts in with a cursor
update; the service relays only to opted-in subscribers of the same puzzle and never exposes the
subject ID. Cursor messages can be dropped under backpressure, expire in the client, and disappear on
leave, disconnect or resynchronization. They do not establish game state.

## Evidence and limitations

- The original adapter, run against the new regression test, entered `connecting` after the collision
  rejection; the test fails before the change and passes after it. The server's collision path rejects
  before installing the candidate and releases newly acquired reservations.
- On an isolated PostgreSQL cluster and separate ports, two independent Chrome sessions saw a remote
  pointer. One player established a temporary preview; after that player attempted an overlapping
  position, the other browser's mutation trace sampled only `ready` with one preview, while the mover
  saw the placement message and the last accepted preview remained. These were synthetic pointer
  events delivered to real browser clients and the real local service, not a visual frame benchmark.
- With the isolated session's allowance set to zero and reloaded, a drag attempt produced zero pointer
  captures, zero preview submissions, no local ghost and the live status “No moves left today”. The
  rotation button was disabled. This setup deliberately changed only the disposable test database.
- This assessment collected no comparable before/after frame-time or latency benchmark; see the
  later [latency assessment](multiplayer-latency-assessment.md) for limited loopback measurements.
  The focused collision counterfactual and observed connection states show
  which reconnect was removed, but do not quantify all visual flashing or network conditions. The
  isolated browser check did not exercise a durable batch confirmation or Undo; the existing suites
  cover those paths and remain relevant.
