-- The game database owns all canonical board data. Chat has its own database.
CREATE TABLE game_boards (
    puzzle_id UUID PRIMARY KEY REFERENCES puzzles(id),
    definition_version BIGINT NOT NULL CHECK (definition_version BETWEEN 0 AND 9007199254740991),
    definition JSONB NOT NULL,
    board_version BIGINT NOT NULL CHECK (board_version BETWEEN 0 AND 9007199254740991),
    closes_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE game_pieces (
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    piece_id UUID NOT NULL,
    PRIMARY KEY (puzzle_id, piece_id)
);
CREATE TABLE game_groups (
    puzzle_id UUID NOT NULL REFERENCES game_boards(puzzle_id),
    group_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 0 AND 9007199254740991),
    translation_x INTEGER NOT NULL CHECK (translation_x BETWEEN -1000000000 AND 1000000000),
    translation_y INTEGER NOT NULL CHECK (translation_y BETWEEN -1000000000 AND 1000000000),
    rotation INTEGER NOT NULL CHECK (rotation BETWEEN 0 AND 359999),
    final_position BOOLEAN NOT NULL,
    PRIMARY KEY (puzzle_id, group_id)
);
CREATE TABLE game_group_pieces (
    puzzle_id UUID NOT NULL,
    group_id UUID NOT NULL,
    piece_id UUID NOT NULL,
    PRIMARY KEY (puzzle_id, piece_id),
    FOREIGN KEY (puzzle_id, piece_id) REFERENCES game_pieces(puzzle_id, piece_id),
    FOREIGN KEY (puzzle_id, group_id) REFERENCES game_groups(puzzle_id, group_id)
);

-- Deferred until commit so a writer can atomically replace groups during a merge.
CREATE FUNCTION check_game_board_membership() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE board_id UUID;
BEGIN
    board_id := COALESCE(NEW.puzzle_id, OLD.puzzle_id);
    IF EXISTS (SELECT 1 FROM game_boards WHERE puzzle_id = board_id) AND (
        NOT EXISTS (SELECT 1 FROM game_pieces WHERE puzzle_id = board_id)
        OR EXISTS (SELECT 1 FROM game_boards b JOIN puzzles p ON p.id = b.puzzle_id
            WHERE b.puzzle_id = board_id AND (b.closes_at <> ((p.puzzle_day + 1)::timestamp AT TIME ZONE 'UTC')
                OR b.definition->>'puzzleId' IS NULL OR (b.definition->>'puzzleId')::uuid <> board_id
                OR (b.definition->>'definitionVersion')::bigint <> b.definition_version))
        OR EXISTS (SELECT 1 FROM game_boards b CROSS JOIN LATERAL jsonb_array_elements(b.definition->'pieces') d
            WHERE b.puzzle_id = board_id AND NOT EXISTS (SELECT 1 FROM game_pieces p
                WHERE p.puzzle_id = board_id AND p.piece_id = (d->>'pieceId')::uuid))
        OR EXISTS (SELECT 1 FROM game_pieces p JOIN game_boards b ON b.puzzle_id = p.puzzle_id
            WHERE p.puzzle_id = board_id AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(b.definition->'pieces') d
                WHERE (d->>'pieceId')::uuid = p.piece_id))
        OR EXISTS (SELECT 1 FROM game_pieces p WHERE p.puzzle_id = board_id AND NOT EXISTS
            (SELECT 1 FROM game_group_pieces m WHERE m.puzzle_id = p.puzzle_id AND m.piece_id = p.piece_id))
        OR EXISTS (SELECT 1 FROM game_groups g WHERE g.puzzle_id = board_id AND NOT EXISTS
            (SELECT 1 FROM game_group_pieces m WHERE m.puzzle_id = g.puzzle_id AND m.group_id = g.group_id))
    ) THEN
        RAISE EXCEPTION 'board must have nonempty groups and exactly one membership per piece' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER board_membership_board AFTER INSERT OR UPDATE ON game_boards
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_game_board_membership();
CREATE CONSTRAINT TRIGGER board_membership_piece AFTER INSERT OR UPDATE OR DELETE ON game_pieces
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_game_board_membership();
CREATE CONSTRAINT TRIGGER board_membership_group AFTER INSERT OR UPDATE OR DELETE ON game_groups
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_game_board_membership();
CREATE CONSTRAINT TRIGGER board_membership_member AFTER INSERT OR UPDATE OR DELETE ON game_group_pieces
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_game_board_membership();

CREATE FUNCTION protect_game_definition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.puzzle_id <> OLD.puzzle_id OR NEW.definition_version <> OLD.definition_version
       OR NEW.definition <> OLD.definition OR NEW.closes_at <> OLD.closes_at THEN
        RAISE EXCEPTION 'active puzzle definition and close time are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER game_definition_immutable BEFORE UPDATE ON game_boards
    FOR EACH ROW EXECUTE FUNCTION protect_game_definition();

CREATE FUNCTION protect_active_puzzle_day() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id <> OLD.id OR NEW.puzzle_day <> OLD.puzzle_day)
       AND EXISTS (SELECT 1 FROM game_boards WHERE puzzle_id = OLD.id) THEN
        RAISE EXCEPTION 'active puzzle identity and day are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER active_puzzle_day_immutable BEFORE UPDATE ON puzzles
    FOR EACH ROW EXECUTE FUNCTION protect_active_puzzle_day();

CREATE FUNCTION protect_board_ownership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.puzzle_id <> OLD.puzzle_id THEN
        RAISE EXCEPTION 'board ownership is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER piece_board_immutable BEFORE UPDATE ON game_pieces
    FOR EACH ROW EXECUTE FUNCTION protect_board_ownership();
CREATE TRIGGER group_board_immutable BEFORE UPDATE ON game_groups
    FOR EACH ROW EXECUTE FUNCTION protect_board_ownership();
CREATE TRIGGER member_board_immutable BEFORE UPDATE ON game_group_pieces
    FOR EACH ROW EXECUTE FUNCTION protect_board_ownership();
