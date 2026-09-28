package io.jsignal.be.ingest;

import io.jsignal.be.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "app.mqtt.enabled=false",
        "app.receiver-name=venezia-1"
})
class IngestWriterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.container();

    private static final Instant TS = Instant.parse("2026-06-12T12:00:00Z");

    @Autowired
    IngestWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM position");
        jdbc.update("DELETE FROM contact");
    }

    private ContactSnapshot aircraft(String icao, String callsign, Double lat, Double lon,
                                     Instant ts, String raw) {
        return new ContactSnapshot("adsb", icao, callsign, ts, "adsb/aircraft/" + icao, raw,
                lat, lon, lat == null ? null : 10972.8f, 231.62842f, 270.5f, null);
    }

    @Test
    void flywayCreatedPositionAsAHypertable() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM timescaledb_information.hypertables
                WHERE hypertable_name = 'position'
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void snapshotWithPositionBecomesOneContactAndOnePositionRow() {
        writer.enqueue(aircraft("4D2228", "AZA123", 45.5, 9.5, TS,
                "{\"icao\":\"4D2228\",\"extra\":1}"));
        writer.flush();

        Map<String, Object> contact = jdbc.queryForMap("""
                SELECT domain, identifier, name, first_seen, last_seen,
                       details ->> 'icao' AS details_icao
                FROM contact WHERE identifier = '4D2228'
                """);
        assertThat(contact.get("domain")).isEqualTo("adsb");
        assertThat(contact.get("name")).isEqualTo("AZA123");
        assertThat(contact.get("details_icao")).isEqualTo("4D2228");

        Map<String, Object> position = jdbc.queryForMap("""
                SELECT p.method, p.domain, p.lat, p.lon, p.alt_m, p.speed_mps, p.course_deg,
                       p.heading_deg, p.rssi_dbm, r.name AS receiver, jsonb_typeof(p.raw) AS raw_type
                FROM position p
                JOIN contact c ON c.id = p.contact_id
                JOIN receiver r ON r.id = p.receiver_id
                WHERE c.identifier = '4D2228'
                """);
        assertThat(position.get("method")).isEqualTo("reported");
        assertThat(position.get("domain")).isEqualTo("adsb");
        assertThat((Double) position.get("lat")).isEqualTo(45.5);
        assertThat(((Number) position.get("alt_m")).floatValue()).isCloseTo(10972.8f, offset(0.01f));
        assertThat(((Number) position.get("speed_mps")).floatValue())
                .isCloseTo(231.62842f, offset(0.01f));
        assertThat(((Number) position.get("course_deg")).floatValue()).isEqualTo(270.5f);
        assertThat(position.get("heading_deg")).isNull();
        assertThat(position.get("rssi_dbm")).isNull();
        assertThat(position.get("receiver")).isEqualTo("venezia-1");
        assertThat(position.get("raw_type")).isEqualTo("object");
    }

    @Test
    void snapshotWithoutPositionStillRecordsTheContact() {
        writer.enqueue(aircraft("AB1234", null, null, null, TS, "{\"icao\":\"AB1234\"}"));
        writer.flush();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact WHERE identifier = 'AB1234'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM position", Long.class)).isEqualTo(0);
    }

    @Test
    void contactKeepsTheLastNonNullNameAndSpansEveryTimestampSeen() {
        writer.enqueue(aircraft("4D2228", null, null, null, TS.minusSeconds(60),
                "{\"icao\":\"4D2228\",\"seq\":1}"));
        writer.enqueue(aircraft("4D2228", "AZA123", 45.5, 9.5, TS,
                "{\"icao\":\"4D2228\",\"seq\":2}"));
        writer.enqueue(aircraft("4D2228", null, 45.6, 9.6, TS.plusSeconds(60),
                "{\"icao\":\"4D2228\",\"seq\":3}"));
        writer.flush();

        Map<String, Object> contact = jdbc.queryForMap("""
                SELECT name, first_seen, last_seen, details ->> 'seq' AS seq
                FROM contact WHERE identifier = '4D2228'
                """);
        assertThat(contact.get("name")).isEqualTo("AZA123");
        assertThat(((java.sql.Timestamp) contact.get("first_seen")).toInstant())
                .isEqualTo(TS.minusSeconds(60));
        assertThat(((java.sql.Timestamp) contact.get("last_seen")).toInstant())
                .isEqualTo(TS.plusSeconds(60));
        assertThat(contact.get("seq")).isEqualTo("3");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM position", Long.class)).isEqualTo(2);
    }

    @Test
    void repeatedFlushesReuseTheSameContactRow() {
        writer.enqueue(aircraft("4D2228", "AZA123", 45.5, 9.5, TS, "{}"));
        writer.flush();
        writer.enqueue(aircraft("4D2228", "AZA123", 45.6, 9.6, TS.plusSeconds(1), "{}"));
        writer.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contact", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM position", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receiver", Long.class)).isEqualTo(1);
    }

    @Test
    void trueHeadingIsStoredSeparatelyFromCourse() {
        writer.enqueue(new ContactSnapshot("ais", "247374900", "MT.MITCHELL", TS,
                "ais/vessel/247374900", "{\"mmsi\":\"247374900\"}",
                45.4, 12.3, null, 6.3277f, 245.1f, 243.0f));
        writer.flush();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT course_deg, heading_deg, alt_m FROM position
                """);
        assertThat(((Number) row.get("course_deg")).floatValue()).isEqualTo(245.1f);
        assertThat(((Number) row.get("heading_deg")).floatValue()).isEqualTo(243.0f);
        assertThat(row.get("alt_m")).isNull();
    }
}
