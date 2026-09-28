package io.jsignal.be.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class PositionRepository {

    private static final String POSITION_COLUMNS = """
            c.identifier AS "trackKey", c.name AS "label", p.lat AS "lat", p.lon AS "lon",
            p.alt_m AS "altM", p.speed_mps AS "speedMps", p.course_deg AS "courseDeg",
            CAST(EXTRACT(EPOCH FROM p.ts) * 1000 AS BIGINT) AS "tsMs"
            """;

    private static final String INSERT = """
            INSERT INTO position
                (ts, contact_id, domain, receiver_id, method,
                 lat, lon, alt_m, speed_mps, course_deg, heading_deg, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """;

    private static final int[] INSERT_TYPES = {
            Types.TIMESTAMP_WITH_TIMEZONE, Types.BIGINT, Types.VARCHAR, Types.SMALLINT, Types.VARCHAR,
            Types.DOUBLE, Types.DOUBLE, Types.REAL, Types.REAL, Types.REAL, Types.REAL, Types.VARCHAR
    };

    private final NamedParameterJdbcTemplate jdbc;

    public PositionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(List<NewPosition> positions) {
        List<Object[]> args = new ArrayList<>(positions.size());
        for (NewPosition p : positions) {
            args.add(new Object[]{
                    utc(p.ts()), p.contactId(), p.domain(), p.receiverId(), p.method(),
                    p.lat(), p.lon(), p.altM(), p.speedMps(), p.courseDeg(), p.headingDeg(), p.rawJson()});
        }
        jdbc.getJdbcTemplate().batchUpdate(INSERT, args, INSERT_TYPES);
    }

    public List<DomainCount> countByDomain() {
        return jdbc.query("""
                SELECT domain, COUNT(*) AS points
                FROM position
                GROUP BY domain
                ORDER BY domain
                """, (rs, n) -> new DomainCount(rs.getString("domain"), rs.getLong("points")));
    }

    public TimeRange findRange(String domain) {
        return jdbc.queryForObject("""
                        SELECT MIN(ts) AS first_ts, MAX(ts) AS last_ts
                        FROM position
                        WHERE domain = :domain
                        """,
                Map.of("domain", domain),
                (rs, n) -> new TimeRange(instant(rs.getTimestamp("first_ts")),
                        instant(rs.getTimestamp("last_ts"))));
    }

    public List<PositionRow> findPositions(String domain, Instant from, Instant to,
                                           String trackKey, int limit) {
        return jdbc.query("""
                SELECT %s
                FROM position p
                JOIN contact c ON c.id = p.contact_id
                WHERE p.domain = :domain AND p.ts >= :from AND p.ts <= :to
                  AND (CAST(:trackKey AS TEXT) IS NULL OR c.identifier = CAST(:trackKey AS TEXT))
                ORDER BY p.ts
                LIMIT :limit
                """.formatted(POSITION_COLUMNS),
                rangeParams(domain, from, to, trackKey).addValue("limit", limit),
                PositionRepository::positionRow);
    }

    public List<PositionRow> findPositionsDownsampled(String domain, Instant from, Instant to,
                                                      String trackKey, int intervalS, int limit) {
        return jdbc.query("""
                SELECT "trackKey", "label", "lat", "lon", "altM", "speedMps", "courseDeg",
                       CAST(EXTRACT(EPOCH FROM ts) * 1000 AS BIGINT) AS "tsMs"
                FROM (
                    SELECT DISTINCT ON (contact_id, bucket) *
                    FROM (
                        SELECT p.contact_id,
                               c.identifier AS "trackKey", c.name AS "label",
                               p.lat AS "lat", p.lon AS "lon", p.alt_m AS "altM",
                               p.speed_mps AS "speedMps", p.course_deg AS "courseDeg", p.ts,
                               FLOOR(EXTRACT(EPOCH FROM p.ts) / :intervalS) AS bucket
                        FROM position p
                        JOIN contact c ON c.id = p.contact_id
                        WHERE p.domain = :domain AND p.ts >= :from AND p.ts <= :to
                          AND (CAST(:trackKey AS TEXT) IS NULL OR c.identifier = CAST(:trackKey AS TEXT))
                    ) pts
                    ORDER BY contact_id, bucket, ts
                ) sampled
                ORDER BY ts
                LIMIT :limit
                """,
                rangeParams(domain, from, to, trackKey)
                        .addValue("intervalS", intervalS)
                        .addValue("limit", limit),
                PositionRepository::positionRow);
    }

    public List<TrackRow> findTracks(String domain, Instant from, Instant to) {
        return jdbc.query("""
                SELECT c.identifier AS "trackKey", c.name AS "label",
                       CAST(c.details AS TEXT) AS "details", COUNT(*) AS "points",
                       CAST(EXTRACT(EPOCH FROM MIN(p.ts)) * 1000 AS BIGINT) AS "firstMs",
                       CAST(EXTRACT(EPOCH FROM MAX(p.ts)) * 1000 AS BIGINT) AS "lastMs"
                FROM position p
                JOIN contact c ON c.id = p.contact_id
                WHERE p.domain = :domain AND p.ts >= :from AND p.ts <= :to
                GROUP BY c.id
                ORDER BY c.identifier
                """,
                rangeParams(domain, from, to, null),
                (rs, n) -> new TrackRow(rs.getString("trackKey"), rs.getString("label"),
                        rs.getString("details"), rs.getLong("points"),
                        rs.getLong("firstMs"), rs.getLong("lastMs")));
    }

    public long dropChunksOlderThan(int days) {
        List<String> dropped = jdbc.query(
                "SELECT drop_chunks('position', older_than => make_interval(days => :days)) AS chunk",
                Map.of("days", days), (rs, n) -> rs.getString("chunk"));
        return dropped.size();
    }

    private static MapSqlParameterSource rangeParams(String domain, Instant from, Instant to,
                                                     String trackKey) {
        return new MapSqlParameterSource()
                .addValue("domain", domain)
                .addValue("from", utc(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", utc(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("trackKey", trackKey, Types.VARCHAR);
    }

    private static OffsetDateTime utc(Instant ts) {
        return ts.atOffset(ZoneOffset.UTC);
    }

    private static PositionRow positionRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PositionRow(rs.getString("trackKey"), rs.getString("label"),
                rs.getDouble("lat"), rs.getDouble("lon"),
                (Float) rs.getObject("altM"), (Float) rs.getObject("speedMps"),
                (Float) rs.getObject("courseDeg"), rs.getLong("tsMs"));
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record NewPosition(Instant ts, long contactId, String domain, Short receiverId,
                              String method, double lat, double lon, Float altM, Float speedMps,
                              Float courseDeg, Float headingDeg, String rawJson) {
    }

    public record DomainCount(String domain, long points) {
    }

    public record TimeRange(Instant firstTs, Instant lastTs) {
    }

    public record PositionRow(String trackKey, String label, double lat, double lon,
                              Float altM, Float speedMps, Float courseDeg, long tsMs) {
    }

    public record TrackRow(String trackKey, String label, String details, long points,
                           long firstMs, long lastMs) {
    }
}
