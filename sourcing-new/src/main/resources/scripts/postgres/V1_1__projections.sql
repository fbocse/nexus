DROP TABLE IF EXISTS projections_progress;

CREATE TABLE IF NOT EXISTS projections_progress (
    projection_id VARCHAR(255) NOT NULL,
    progress json NOT NULL,
    PRIMARY KEY(projection_id)
);


DROP TABLE IF EXISTS projections_failures ;
CREATE TABLE IF NOT EXISTS projections_failures (
    ordering BIGSERIAL,
    projection_id VARCHAR(255) NOT NULL,
    akka_offset json NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_nr BIGINT NOT NULL,
    value json NOT NULL
);

CREATE INDEX IF NOT EXISTS projections_projection_id_idx ON projections_failures(projection_id);
CREATE INDEX IF NOT EXISTS projections_failures_ordering_idx ON projections_failures(ordering);
