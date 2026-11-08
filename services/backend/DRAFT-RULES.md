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

The private-local coordinator integrates this engine with authenticated turns, process-local leases,
preview generation fences, cancellation and the confirmation journal. A PostgreSQL advisory lock
rejects a second local writer; this is not distributed leader election or a cross-process fencing
epoch. The service reserves turn and merged-group IDs and retains cancel and undo receipts in the
game database. The frontend owner still needs to integrate the shared turn and geometry contract,
including real mouse and touch coverage. Chat and public hosting remain separate.
