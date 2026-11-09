-- Keep every authoritative definition, including those that predate shape editing.
CREATE TABLE game_definition_history (
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    definition_version BIGINT NOT NULL CHECK (definition_version BETWEEN 0 AND 9007199254740991),
    board_version BIGINT NOT NULL CHECK (board_version BETWEEN 0 AND 9007199254740991),
    definition JSONB NOT NULL,
    PRIMARY KEY (puzzle_id, definition_version),
    UNIQUE (puzzle_id, board_version)
);
INSERT INTO game_definition_history(puzzle_id, definition_version, board_version, definition)
SELECT puzzle_id, definition_version, board_version, definition FROM game_boards;

-- A shape command is idempotent even if its HTTP response is lost.
CREATE TABLE game_shape_requests (
    subject_id UUID NOT NULL REFERENCES game_subjects(subject_id),
    request_id UUID NOT NULL,
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    canonical_payload TEXT NOT NULL,
    result JSONB NOT NULL,
    PRIMARY KEY (subject_id, request_id)
);

CREATE OR REPLACE FUNCTION protect_game_definition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.puzzle_id <> OLD.puzzle_id OR NEW.closes_at <> OLD.closes_at THEN
        RAISE EXCEPTION 'active puzzle identity and close time are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.definition_version <> OLD.definition_version OR NEW.definition <> OLD.definition THEN
        IF NEW.definition_version <> OLD.definition_version + 1
           OR NEW.board_version <> OLD.board_version + 1
           OR NOT EXISTS (SELECT 1 FROM game_definition_history h
               WHERE h.puzzle_id = NEW.puzzle_id AND h.definition_version = NEW.definition_version
                 AND h.board_version = NEW.board_version AND h.definition = NEW.definition) THEN
            RAISE EXCEPTION 'definition changes require an ordered history entry' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
