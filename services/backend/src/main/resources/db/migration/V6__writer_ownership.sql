CREATE TABLE game_writer_ownership (
  singleton boolean PRIMARY KEY CHECK (singleton),
  owner uuid NOT NULL
);
