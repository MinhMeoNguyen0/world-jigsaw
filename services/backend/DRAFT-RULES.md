# Internal draft rules

`DraftRules` is an internal Java port of the local TypeScript draft semantics. It handles movement,
repeated adjustment, free outer sorting, surface entry, attachment, whole-group movement, undo,
quota accounting and confirmation against the latest touched group revisions. Attachment uses the
server-owned provisional policy in [LOCAL-GEOMETRY.md](LOCAL-GEOMETRY.md), rather than accepting a
client-provided tolerance. The returned snapshot is a proposal: it is not a durable write, and
collision validation is performed separately by the confirmation journal.

The shared `packages/rules/vectors/draft-v1.json` scenarios run against both implementations, in
addition to generated 60- and 80-piece boards and the existing quarter-turn arithmetic vectors. The
Java tests use repeated quarter turns on all four seeded orientations. These are rules-level tests,
not evidence of actual mouse or touch controls, browser integration, or multiplayer.

No public turn or mutation endpoint uses this class. The process-local `TurnLeaseRegistry` provides
bounded leases and preview generations, but does not itself validate rules or coordinate with
network delivery or database admission. A coordinator still needs authenticated identity, an
ordered preview stream and generation fences, authoritative input
validation, cancellation serialized against Confirm, replay-safe outcomes, lifecycle handling,
collision checks against all concurrent previews and a gap-free snapshot handoff. The coordinator
must prevent reused merged group and action IDs even after a group has disappeared; this draft
component only checks current and base group IDs. Until that integration and its end-to-end tests
exist, moving and confirming pieces remain unavailable.

The `GameIdentityLedger` retains reserved turn and group identifiers, plus identity-scoped cancel
and undo receipts, in PostgreSQL. A future coordinator must supply durably reserved turn IDs to
the lease registry, reserve merged group IDs before broadcasting them, and serialize receipt
recording with each in-memory decision. The registry's in-process test overload generates random
IDs and does not establish the durable non-reuse guarantee. Confirm pinning must start only after
admission under the board lock; it must end only after commit or known rollback. These primitives
are single-process and do not implement leader election, a cross-process fence or event delivery.
