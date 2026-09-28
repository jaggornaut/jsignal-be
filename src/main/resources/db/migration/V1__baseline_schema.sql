CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE receiver (
    id       smallserial PRIMARY KEY,
    name     text NOT NULL UNIQUE,
    lat      double precision,
    lon      double precision,
    alt_m    real,
    active   boolean NOT NULL DEFAULT true,
    notes    text
);

CREATE TABLE contact (
    id         bigserial PRIMARY KEY,
    domain     text NOT NULL,
    identifier text NOT NULL,
    name       text,
    first_seen timestamptz NOT NULL,
    last_seen  timestamptz NOT NULL,
    details    jsonb,
    CONSTRAINT uk_contact_domain_identifier UNIQUE (domain, identifier)
);

CREATE INDEX idx_contact_last_seen ON contact (last_seen DESC);

CREATE TABLE position (
    ts          timestamptz NOT NULL,
    contact_id  bigint NOT NULL REFERENCES contact (id),
    domain      text NOT NULL,
    receiver_id smallint REFERENCES receiver (id),
    method      text NOT NULL DEFAULT 'reported',
    lat         double precision NOT NULL,
    lon         double precision NOT NULL,
    alt_m       real,
    speed_mps   real,
    course_deg  real,
    heading_deg real,
    rssi_dbm    real,
    raw         jsonb,
    CONSTRAINT ck_position_method CHECK (method IN ('reported', 'computed', 'predicted'))
);

SELECT create_hypertable('position', by_range('ts', INTERVAL '1 day'));

CREATE INDEX idx_position_domain_ts ON position (domain, ts DESC);
CREATE INDEX idx_position_contact_ts ON position (contact_id, ts DESC);

ALTER TABLE position SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'contact_id',
    timescaledb.compress_orderby = 'ts DESC'
);

SELECT add_compression_policy('position', INTERVAL '7 days');
