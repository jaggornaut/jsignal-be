BEGIN;

INSERT INTO receiver (name, lat, lon, notes)
VALUES ('venezia-1', 45.488925, 12.164892, 'ref_position from adsb-module.yaml')
ON CONFLICT (name) DO NOTHING;

INSERT INTO contact (domain, identifier, name, first_seen, last_seen, details)
SELECT t.domain,
       t.track_key,
       (ARRAY_AGG(t.label ORDER BY t.ts DESC)
            FILTER (WHERE t.label IS NOT NULL))[1],
       MIN(t.ts),
       MAX(t.ts),
       (ARRAY_AGG(t.raw ORDER BY t.ts DESC)
            FILTER (WHERE t.raw IS NOT NULL))[1]
FROM track_points t
GROUP BY t.domain, t.track_key
ON CONFLICT (domain, identifier) DO NOTHING;

INSERT INTO position
    (ts, contact_id, domain, receiver_id, method,
     lat, lon, alt_m, speed_mps, course_deg, heading_deg, raw)
SELECT t.ts,
       c.id,
       t.domain,
       r.id,
       'reported',
       t.lat,
       t.lon,
       t.alt * 0.3048,
       t.speed_kts * 0.514444,
       t.heading_deg,
       CASE WHEN t.domain = 'ais'
                 AND jsonb_typeof(t.raw -> 'heading_deg') = 'number'
                 AND (t.raw ->> 'heading_deg')::double precision >= 0
                 AND (t.raw ->> 'heading_deg')::double precision < 360
            THEN (t.raw ->> 'heading_deg')::real
       END,
       t.raw
FROM track_points t
JOIN contact c ON c.domain = t.domain AND c.identifier = t.track_key
CROSS JOIN (SELECT id FROM receiver WHERE name = 'venezia-1') r
WHERE t.lat IS NOT NULL AND t.lon IS NOT NULL;

COMMIT;
