BEGIN;

CREATE OR REPLACE FUNCTION track_points_readonly() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'track_points is archived and read-only, superseded by contact and position';
END;
$$;

DROP TRIGGER IF EXISTS trg_track_points_readonly ON track_points;

CREATE TRIGGER trg_track_points_readonly
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON track_points
    FOR EACH STATEMENT EXECUTE FUNCTION track_points_readonly();

-- The trigger above is what enforces read-only. A REVOKE here would be a no-op:
-- jsignal owns track_points, and an owner bypasses table privileges.

COMMIT;
