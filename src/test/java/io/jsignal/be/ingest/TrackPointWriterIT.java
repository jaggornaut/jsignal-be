package io.jsignal.be.ingest;

import io.jsignal.be.entity.TrackPoint;
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

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.mqtt.enabled=false")
class TrackPointWriterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TrackPointWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void enqueuedPointsArePersistedWithJsonbRawPayload() {
        Instant ts = Instant.parse("2026-06-12T12:00:00Z");
        writer.enqueue(new TrackPoint("adsb", "4D2228", "AZA123",
                45.5, 9.5, 36000, 450.25f, 270.5f, ts,
                "adsb/aircraft/4D2228", "{\"icao\":\"4D2228\",\"extra\":1}"));
        writer.enqueue(new TrackPoint("adsb", "AB1234", null,
                null, null, null, null, null, ts.plusSeconds(1),
                "adsb/aircraft/AB1234", "{}"));

        writer.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM track_points", Long.class)).isEqualTo(2);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT domain, track_key, label, lat, lon, alt, speed_kts, heading_deg,
                       raw ->> 'icao' AS raw_icao, jsonb_typeof(raw) AS raw_type
                FROM track_points WHERE track_key = '4D2228'
                """);
        assertThat(row.get("domain")).isEqualTo("adsb");
        assertThat(row.get("label")).isEqualTo("AZA123");
        assertThat((Double) row.get("lat")).isEqualTo(45.5);
        assertThat((Double) row.get("lon")).isEqualTo(9.5);
        assertThat(row.get("alt")).isEqualTo(36000);
        assertThat(((Number) row.get("speed_kts")).doubleValue()).isEqualTo(450.25);
        assertThat(((Number) row.get("heading_deg")).doubleValue()).isEqualTo(270.5);
        assertThat(row.get("raw_icao")).isEqualTo("4D2228");
        assertThat(row.get("raw_type")).isEqualTo("object");

        Map<String, Object> sparse = jdbc.queryForMap(
                "SELECT label, lat, lon, alt FROM track_points WHERE track_key = 'AB1234'");
        assertThat(sparse.get("label")).isNull();
        assertThat(sparse.get("lat")).isNull();
        assertThat(sparse.get("alt")).isNull();
    }
}
