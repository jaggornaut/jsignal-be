package io.jsignal.be.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "app.mqtt.enabled=false",
        "app.positions-max-rows=10"
})
class PositionsApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long BASE_MS = 1_718_000_000_000L;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM track_points");
    }

    private void insertPoint(String trackKey, String label, Double lat, Double lon, long tsMs) {
        jdbc.update("""
                        INSERT INTO track_points
                            (id, domain, track_key, label, lat, lon, alt, speed_kts, heading_deg, ts, topic, raw)
                        VALUES (nextval('track_points_id_seq'), 'adsb', ?, ?, ?, ?, 36000, 450.25, 270.5, ?, 'adsb/aircraft/' || ?, '{}'::jsonb)
                        """,
                trackKey, label, lat, lon, Timestamp.from(Instant.ofEpochMilli(tsMs)), trackKey);
    }

    @Test
    @SuppressWarnings("unchecked")
    void positionsReturnsRowsInTsOrderWithExpectedStructure() {
        insertPoint("4D2228", "AZA123", 45.1, 9.1, BASE_MS + 2000);
        insertPoint("4D2228", "AZA123", 45.0, 9.0, BASE_MS);
        insertPoint("4D2228", null, null, null, BASE_MS + 1000);

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/adsb/positions?from_ms={f}&to_ms={t}", Map.class, BASE_MS, BASE_MS + 10_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> positions =
                (List<Map<String, Object>>) response.getBody().get("positions");
        assertThat(positions).hasSize(2);

        Map<String, Object> first = positions.get(0);
        assertThat(first.get("track_key")).isEqualTo("4D2228");
        assertThat(first.get("label")).isEqualTo("AZA123");
        assertThat((Double) first.get("lat")).isEqualTo(45.0);
        assertThat((Double) first.get("lon")).isEqualTo(9.0);
        assertThat(first.get("alt")).isEqualTo(36000);
        assertThat(((Number) first.get("speed_kts")).doubleValue()).isEqualTo(450.25);
        assertThat(((Number) first.get("heading_deg")).doubleValue()).isEqualTo(270.5);
        assertThat(((Number) first.get("ts_ms")).longValue()).isEqualTo(BASE_MS);
        assertThat(((Number) positions.get(1).get("ts_ms")).longValue()).isEqualTo(BASE_MS + 2000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void downsamplingKeepsAtMostOneRowPerTrackAndBucket() {
        for (int i = 0; i < 8; i++) {
            insertPoint("4D2228", "AZA123", 45.0 + i, 9.0, BASE_MS + i * 1000L);
        }
        insertPoint("AB1234", "XYZ", 50.0, 8.0, BASE_MS + 1000);

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/adsb/positions?from_ms={f}&to_ms={t}&interval_s=5",
                Map.class, BASE_MS, BASE_MS + 10_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> positions =
                (List<Map<String, Object>>) response.getBody().get("positions");
        assertThat(positions).hasSize(3);

        List<Map<String, Object>> forKey = positions.stream()
                .filter(p -> p.get("track_key").equals("4D2228")).toList();
        assertThat(forKey).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void trackKeyFilterReturnsOnlyThatTrack() {
        insertPoint("4D2228", "AZA123", 45.0, 9.0, BASE_MS);
        insertPoint("AB1234", "XYZ", 50.0, 8.0, BASE_MS);

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/adsb/positions?from_ms={f}&to_ms={t}&track_key=AB1234",
                Map.class, BASE_MS - 1000, BASE_MS + 1000);

        List<Map<String, Object>> positions =
                (List<Map<String, Object>>) response.getBody().get("positions");
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).get("track_key")).isEqualTo("AB1234");
    }

    @Test
    void exceedingRowCapReturns413WithExactErrorBody() {
        for (int i = 0; i < 11; i++) {
            insertPoint("4D2228", "AZA123", 45.0, 9.0, BASE_MS + i * 1000L);
        }

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/adsb/positions?from_ms={f}&to_ms={t}", Map.class, BASE_MS, BASE_MS + 60_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("error")).isEqualTo("too many rows, increase interval_s");
    }

    @Test
    void invalidParametersReturn400() {
        assertThat(rest.getForEntity("/api/v1/adsb/positions?to_ms=2", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/adsb/positions?from_ms=abc&to_ms=2", Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/adsb/positions?from_ms=2&to_ms=2", Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void domainsRangeTracksAndHealthEndpoints() {
        insertPoint("4D2228", "AZA123", 45.0, 9.0, BASE_MS);
        insertPoint("4D2228", "AZA123", 45.1, 9.1, BASE_MS + 5000);

        ResponseEntity<Map> domains = rest.getForEntity("/api/v1/domains", Map.class);
        assertThat(domains.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> domainList =
                (List<Map<String, Object>>) domains.getBody().get("domains");
        assertThat(domainList).hasSize(1);
        assertThat(domainList.get(0).get("domain")).isEqualTo("adsb");
        assertThat(((Number) domainList.get(0).get("points")).longValue()).isEqualTo(2);

        ResponseEntity<Map> range = rest.getForEntity("/api/v1/adsb/range", Map.class);
        assertThat(range.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) range.getBody().get("from_ms")).longValue()).isEqualTo(BASE_MS);
        assertThat(((Number) range.getBody().get("to_ms")).longValue()).isEqualTo(BASE_MS + 5000);

        assertThat(rest.getForEntity("/api/v1/ais/range", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> tracks = rest.getForEntity(
                "/api/v1/adsb/tracks?from_ms={f}&to_ms={t}", Map.class, BASE_MS, BASE_MS + 10_000);
        List<Map<String, Object>> trackList =
                (List<Map<String, Object>>) tracks.getBody().get("tracks");
        assertThat(trackList).hasSize(1);
        Map<String, Object> track = trackList.get(0);
        assertThat(track.get("track_key")).isEqualTo("4D2228");
        assertThat(track.get("label")).isEqualTo("AZA123");
        assertThat(((Number) track.get("points")).longValue()).isEqualTo(2);
        assertThat(((Number) track.get("first_ms")).longValue()).isEqualTo(BASE_MS);
        assertThat(((Number) track.get("last_ms")).longValue()).isEqualTo(BASE_MS + 5000);

        ResponseEntity<Map> health = rest.getForEntity("/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void corsAllowsAnyOriginOnGet() {
        RequestEntity<Void> request = RequestEntity
                .get(URI.create(rest.getRootUri() + "/api/v1/domains"))
                .header("Origin", "http://example.com")
                .build();
        ResponseEntity<Map> response = rest.exchange(request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNotNull();
    }
}
