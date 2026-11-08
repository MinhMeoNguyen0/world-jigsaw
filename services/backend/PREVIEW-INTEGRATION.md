# Transient previews

The application coordinator uses `PreviewGeneration` and process-local leases to fan out temporary
v1 previews. Each accepted action is validated against the canonical board, quota, reservations,
workspace and the provisional collision policy. Subscribers receive a consistent snapshot,
watermark and active previews, then ordered preview, clear, board and rollover messages. An undo
fences the old generation and re-emits surviving actions; cancellation clears the draft without
restoring an old board. An admitted Confirm pins its previews until the transaction's known result.

The protocol's explicit join request and independent outgoing sequence numbers are documented in
[TURN-WIRE.md](TURN-WIRE.md). The service is private-local and single-writer; session identity,
geometry thresholds, error handling and resilience are not a public hosting security review. The
frontend and chat have not been integrated by this backend change.

## Separate preview demo

`./gradlew previewDemo` runs the earlier disposable test server on an ephemeral `127.0.0.1` port.
Its `preview|sequence|action-uuid|x` and `cancel` text messages are a separate demonstration, not
the v1 game protocol. It has no database, real identity, quota or Confirm and must not be exposed.
