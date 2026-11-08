# Transient game previews

`PreviewGeneration` stores the latest validated preview for each action in one turn generation.
Its snapshot is ordered by sequence, so reconnecting clients can receive every active action even
when one action was updated more recently than another. Clearing a generation drops its overlays
and permanently rejects late frames. The data is process-local and never changes the saved board.

This is an internal building block, not a game endpoint or a working game. The application
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

## Private transport demo

Run `./gradlew previewDemo` from `services/backend`. It prints an ephemeral WebSocket address bound
only to `127.0.0.1`. Connect two local non-browser WebSocket clients to that address. On connection
each receives `active` followed by zero or more `|sequence,action-uuid,x` entries in sequence
order. Send `preview|sequence|action-uuid|x` to update a piece, or `cancel` to clear the turn.
Every client receives the resulting full state; after cancellation the state is `cleared` and late
frames cannot restore it. Disconnecting and reconnecting sends the current state. The focused
integration test is `./gradlew test --tests com.worldjigsaw.game.PreviewDemoServerTest`.

This disposable test application uses one shared provisional turn and trusts all local clients as
the same player; it rejects browser Origin headers but has no authentication or public security.
Its compact text protocol is for the demo, not the v1 game protocol. It has no game rules,
canonical board, durable storage, expiry, Confirm, or production resource controls. It must not be
exposed through a proxy or used as a hosted game service. Stop it with Ctrl-C; restarting loses all
previews.
