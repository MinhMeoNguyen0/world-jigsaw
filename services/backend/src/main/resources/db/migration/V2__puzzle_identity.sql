-- puzzle_day is the UTC calendar date assigned by the game service.
CREATE TABLE puzzles (
    id UUID PRIMARY KEY,
    puzzle_day DATE NOT NULL UNIQUE
);
