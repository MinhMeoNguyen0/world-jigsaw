-- Internal game-owned subjects, quota and confirmation journal. No chat data is stored here.
-- Results are retained indefinitely so a previously used command ID cannot execute again.
CREATE TABLE game_subjects (
    subject_id UUID PRIMARY KEY
);
CREATE TABLE game_allowances (
    subject_id UUID NOT NULL REFERENCES game_subjects(subject_id),
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    used INTEGER NOT NULL DEFAULT 0 CHECK (used BETWEEN 0 AND 5),
    PRIMARY KEY (subject_id, puzzle_id)
);
CREATE TABLE game_commands (
    subject_id UUID NOT NULL REFERENCES game_subjects(subject_id),
    command_id UUID NOT NULL,
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    canonical_payload TEXT NOT NULL,
    result JSONB NOT NULL,
    PRIMARY KEY (subject_id, command_id)
);
CREATE TABLE game_events (
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    board_version BIGINT NOT NULL CHECK (board_version BETWEEN 0 AND 9007199254740991),
    event_id UUID NOT NULL,
    event JSONB NOT NULL,
    PRIMARY KEY (puzzle_id, board_version),
    UNIQUE (puzzle_id, event_id)
);

-- Random session secrets are never stored: only their SHA-256 digests are retained.
CREATE TABLE game_sessions (
    token_hash CHAR(64) PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES game_subjects(subject_id),
    display_name TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
