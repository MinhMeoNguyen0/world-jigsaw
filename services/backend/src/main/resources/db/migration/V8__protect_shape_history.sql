-- Definition versions and successful request receipts are an append-only journal.
CREATE FUNCTION protect_shape_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'shape history is immutable' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER game_definition_history_immutable
    BEFORE UPDATE OR DELETE ON game_definition_history
    FOR EACH ROW EXECUTE FUNCTION protect_shape_history();

CREATE TRIGGER game_shape_requests_immutable
    BEFORE UPDATE OR DELETE ON game_shape_requests
    FOR EACH ROW EXECUTE FUNCTION protect_shape_history();
