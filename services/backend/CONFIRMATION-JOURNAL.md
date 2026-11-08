# Internal confirmation journal

The journal is internal; clients cannot submit a planner. The private-local coordinator authenticates
the session, validates the v1 command, and serializes lease, preview, cancel and stream operations
with this transaction. The trusted rules planner checks action history, movement, attachment,
workspace, membership, revisions and quota. Structural and collision checks are additional defenses.
The provisional geometry policy and its remaining limits are in [LOCAL-GEOMETRY.md](LOCAL-GEOMETRY.md).

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

Journal tests use real isolated PostgreSQL, a trusted Java draft, and stub planners. They cover lost
replies, conflicting retries, concurrent subjects, forced rollback, UTC admission, and legacy data
preservation. Separate service integration tests exercise server restart and WebSocket fanout with
independent HTTP clients. No public security or resource-exhaustion review has been performed.
