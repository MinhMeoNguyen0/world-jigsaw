# Local turn wire interpretation

This is the private-local service's interpretation of the shared schema. The web client's `LiveGame` adapter
uses this wire. The existing `PreviewSubmit` action has no separate drag/drop/attach field.
A position frame should not irreversibly join a group merely because a pointer passed a neighbor.

For a normal move or adjustment, `targetId` identifies a draft group or one of its pieces and
`affectedPieceIds` names exactly that draft group's complete members. Its `baseRevisions` names
all canonical source groups represented by those members. To request a join, the same frame names
in `affectedPieceIds` the complete union of the moving group and intended target groups and names
all their canonical source revisions. The server treats this as a request, not as proof: it must
acquire all source leases atomically, validate revision and membership, require each join to have a
reciprocal neighboring edge within the server's snap tolerance, and validate the resulting complete
board against the collision policy. It authors any merged group ID and returns its own previews.
Omitting the union is a movement frame; it never silently joins while dragging. Busy, stale, invalid,
or collision outcomes reject the frame without mutating the preceding draft or consuming a move.
If the bounded join search cannot decide, it reports a retryable `INTERNAL_ERROR`; the player can
retry the join in smaller steps without losing the draft.

A server preview for an action reflects its current draft component, even after joining: `targetId`
is the server-authored draft group ID for a multi-piece group, or the piece ID for a singleton;
`affectedPieceIds` and `baseRevisions` describe the entire component. Independently introduced
actions remain distinct and keep their own charges. Each accepted change can re-emit the latest
preview for each affected action. The server allocates strictly increasing outgoing preview
sequences within each generation; the client's submitted sequence is tracked independently for
rejecting stale submissions and is reported on rejection and v2 acceptance. A reconnect receives
every current action in outgoing sequence order.

The revision-correlated draft summary reports pending moves and the IDs of the actions currently
charged. Clients must correlate it with the accepted draft before using it to gate movement.

Undo fences and clears the old generation, then re-emits surviving previews under the new generation;
see the [shared contract](../../packages/contracts/README.md) for joined-action behavior. Cancel clears only the draft and reveals
the latest canonical board. Confirm must match the server's current action set and turn revision,
use the original command ID for retries, validate again under the board lock, persist the board,
quota, event and command result atomically, then emit the event and fence the preview. A response
lost after commit must be resolved from the durable command ID. None of this document replaces the
shared v1 schema or authorizes public exposure.

## Versioned identity and acceptance handoff

A client opts in by sending `subscribe.v2` in the v1 envelope. The corresponding
`subscribed.v2` includes the board snapshot and previews plus the authenticated subject's
allowance, nullable own turn, accepted actions, draft revision and next client sequence at one
coordinator handoff. Identity-changing operations invalidate affected v2 subscriptions with
`resyncRequired`; clients must resubscribe before editing. The original `subscribe` and
`subscribed` shapes remain unchanged.

After installing a valid `preview.submit`, the coordinator sends only its originating v2 socket
`preview.accepted`, echoing the submitted sequence and action ID along with the turn identity,
the authoritative action set and its draft revision. The broadcast preview sequence remains a
different server sequence. Each accepted mutation and undo advances the draft revision. The
closed `ConfirmV2` payload includes that revision in addition to the existing turn revision and
complete accepted action set; both checks occur under the coordinator lock before persistence.
An intervening same-session tab therefore fences a stale command even if the action set returns
to the same values. Existing v1 confirmation retains its original semantics. Clients must not
infer acceptance from a broadcast and must reconcile an unknown result before another save.

Undo uses the closed `UndoV2` payload with the observed draft revision. The service rejects the
original `Undo` shape because it cannot fence a concurrent preview; a retry uses the same request
ID and payload. Both initial execution and receipt recovery check the revision before mutation.

## Ephemeral pointers

Subscribed clients opt in by sending `cursor.update` with a board-coordinate position or null. The
stream relays `cursor.presence` only to other opted-in connections subscribed to the same puzzle. The
server supplies an opaque connection ID and display name; no pointer data is persisted. Null clears a
pointer. The relay may drop presence under backpressure; clients expire stale positions and clear them
on disconnect or resynchronization. Existing clients receive no cursor messages until they opt in.

A correlated `INVALID_ACTION` rejection with message `Forbidden piece overlap`, `Group outside
workspace`, `Cannot join selected groups`, or `Not adjacent or outside snap tolerance` is definitive
for the submitted frame: the candidate was not installed and the previous draft and revision remain intact.
The client may continue from that acknowledged draft with a fresh sequence. Other rejections and
unknown outcomes still require the documented reconciliation; the message is part of this private
wire interpretation and must change together with clients if the server changes it.
