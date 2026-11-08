# Transient game previews

`PreviewGeneration` stores the latest validated preview for each action in one turn generation.
Its snapshot is ordered by sequence, so reconnecting clients can receive every active action even
when one action was updated more recently than another. Clearing a generation drops its overlays
and permanently rejects late frames. The data is process-local and never changes the saved board.

This is an internal building block, not a WebSocket endpoint or a working game. The application
must authenticate and authorize the player, validate the action and its geometry, enforce quota,
leases and expiry, and serialize these operations with Confirm, cancellation and lifecycle changes
before calling it. It must enforce the contract's action capacity and bound memory. The component
does not validate those rules or automatically expire previews. A new generation must be created
and owned by the turn coordinator after undo; the old one must first be fenced. Restart discards
all overlays.

Integration must also coordinate the canonical snapshot and its event watermark with the preview
snapshot, queue or replay events across subscription, and order each accepted preview or clear
with its broadcast. A `snapshot()` call alone is not an atomic network handoff. Confirm must clear
the overlay only in coordination with the actual durable outcome; it must not turn a preview into
a claimed save. The generator, rules engine, database/import/read API, session identity and live
transport are separate pending work. See `packages/contracts/README.md` for the v1 contract and
its unresolved integration constraints.
