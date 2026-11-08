-- Non-reusable IDs and request receipts are game-owned; existing board data is unchanged.
CREATE TABLE game_turn_ids (
    turn_id UUID PRIMARY KEY,
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    subject_id UUID NOT NULL REFERENCES game_subjects(subject_id)
);
CREATE TABLE game_group_ids (
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    group_id UUID NOT NULL,
    PRIMARY KEY (puzzle_id, group_id)
);
INSERT INTO game_group_ids(puzzle_id, group_id) SELECT puzzle_id, group_id FROM game_groups;
CREATE FUNCTION remember_game_group_id() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO game_group_ids(puzzle_id, group_id) VALUES (NEW.puzzle_id, NEW.group_id)
        ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER remember_game_group AFTER INSERT ON game_groups
    FOR EACH ROW EXECUTE FUNCTION remember_game_group_id();

-- Retained without expiry for the local prototype, including canceled or undone requests.
CREATE TABLE game_requests (
    subject_id UUID NOT NULL REFERENCES game_subjects(subject_id),
    request_id UUID NOT NULL,
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    request_kind TEXT NOT NULL CHECK (request_kind IN ('cancel', 'undo')),
    canonical_payload TEXT NOT NULL,
    result JSONB NOT NULL,
    PRIMARY KEY (subject_id, request_id)
);
