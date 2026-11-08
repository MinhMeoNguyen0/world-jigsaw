# Internal confirmation journal

This is an internal persistence foundation, not a multiplayer endpoint. No client can submit a
planner or invoke it over HTTP. A future coordinator must authenticate its subject, validate the
complete v1 command and all IDs, provide a trusted rules planner, and serialize lease, preview,
cancel and stream operations with this transaction. The planner must check action history,
movement, attachment, workspace, group membership and revisions, and calculate the quota charge
from the locked current snapshot. The journal's structural and collision checks are additional
defenses, not a replacement for these rules. The provisional geometry policy has not yet passed the
cross-client acceptance gate described in [LOCAL-GEOMETRY.md](LOCAL-GEOMETRY.md).

The journal serializes each subject across puzzles, so a command ID is never reused for another
payload or puzzle. It permanently retains canonical JSON payloads and outcomes in the game database;
this is a local prototype retention decision, not a public privacy policy. A duplicate with identical
canonical content returns the original outcome after restart; a different payload returns
`COMMAND_CONFLICT`. Subject identifiers and command content must not be written to chat storage.

For a new command, it locks the puzzle board before reading the server UTC clock. An admission
strictly before close may finish after close; one at or after close gets a durable rejection. It
reads the current snapshot and quota under that lock, invokes the trusted planner, validates the
proposed board and provisional collision policy, and updates board membership, version, quota,
event and outcome in one transaction. Failure rolls back all writes. Legacy non-quarter-turn
orientations return `INVALID_ACTION` with message `UNSUPPORTED_LEGACY_ORIENTATION` and preserve the
board. That message uses an existing v1 error code because the closed v1 schema has no dedicated
legacy code.

Tests use a real isolated PostgreSQL container and stub planners to exercise the journal. They cover
lost replies with a newly constructed journal, conflicting retries, two concurrent subjects, a forced
post-write database failure, pre-close admission finishing after close, waiting for the board lock
across close, and legacy data preservation. Stub planners are not evidence of a working game rules
integration, a process restart, WebSocket fanout, or two independent browser clients. No public
security or resource-exhaustion review has been performed.
