package io.jsignal.be.controller;

import io.jsignal.be.TestDatabase;
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
import static org.assertj.core.data.Offset.offset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "app.mqtt.enabled=false",
        "app.positions-max-rows=10"
})
class PositionsApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.container();

    private static final long BASE_MS = 1_718_000_000_000L;

    private static final float ALT_36000FT_M = 10972.8f;
    private static final float SPEED_450KTS_MPS = 231.62842f;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM position");
        jdbc.update("DELETE FROM contact");
    }

    private long contact(String domain, String identifier, String name, String details) {
        return jdbc.queryForObject("""
                        INSERT INTO contact (domain, identifier, name, first_seen, last_seen, details)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                        RETURNING id
                        """, Long.class,
                domain, identifier, name,
                Timestamp.from(Instant.ofEpochMilli(BASE_MS)),
                Timestamp.from(Instant.ofEpochMilli(BASE_MS)), details);
    }

    private void position(long contactId, String domain, double lat, double lon, long tsMs) {
        jdbc.update("""
                        INSERT INTO position
                            (ts, contact_id, domain, method, lat, lon, alt_m, speed_mps, course_deg)
                        VALUES (?, ?, ?, 'reported', ?, ?, ?, ?, 270.5)
                        """,
                Timestamp.from(Instant.ofEpochMilli(tsMs)), contactId, domain, lat, lon,
                ALT_36000FT_M, SPEED_450KTS_MPS);
        }

    @Test
    @SuppressWarnings("unchecked")
    void tracksExposeDomainSpecificFieldsFromTheContactDetails() {
        long id = contact("ais", "247374900", "MT.MITCHELL", """
                {"mmsi":"247374900","vessel_name":"MT.MITCHELL","nav_status":"under way using engine",\
                "destination":"VENEZIA","ship_type":"cargo"}""");
        position(id, "ais", 45.4, 12.3, BASE_MS);
        position(id, "ais", 45.4, 12.3, BASE_MS + 5_000);

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/ais/tracks?from_ms={f}&to_ms={t}", Map.class, BASE_MS, BASE_MS + 10_000);

        List<Map<String, Object>> tracks =
                (List<Map<String, Object>>) response.getBody().get("tracks");
        assertThat(tracks).hasSize(1);

        Map<String, Object> track = tracks.get(0);
        assertThat(track.get("track_key")).isEqualTo("247374900");
        assertThat(track.get("label")).isEqualTo("MT.MITCHELL");
        assertThat(((Number) track.get("points")).longValue()).isEqualTo(2);
        assertThat(((Number) track.get("first_ms")).longValue()).isEqualTo(BASE_MS);
        assertThat(((Number) track.get("last_ms")).longValue()).isEqualTo(BASE_MS + 5_000);

        Map<String, Object> attrs = (Map<String, Object>) track.get("attrs");
        assertThat(attrs)
                .containsEntry("nav_status", "under way using engine")
                .containsEntry("destination", "VENEZIA")
                .containsEntry("ship_type", "cargo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void positionsReturnsRowsInTsOrderWithFieldNamesAndUnitsUnchanged() {
        long id = contact("adsb", "4D2228", "AZA123", "{}");
        position(id, "adsb", 45.1, 9.1, BASE_MS + 2000);
        position(id, "adsb", 45.0, 9.0, BASE_MS);
        contact("adsb", "AB1234", null, "{}");

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
        assertThat(((Number) first.get("speed_kts")).doubleValue())
                .isCloseTo(450.25, offset(0.01));
        assertThat(((Number) first.get("heading_deg")).doubleValue()).isEqualTo(270.5);
        assertThat(((Number) first.get("ts_ms")).longValue()).isEqualTo(BASE_MS);
        assertThat(((Number) positions.get(1).get("ts_ms")).longValue()).isEqualTo(BASE_MS + 2000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void downsamplingKeepsAtMostOneRowPerTrackAndBucket() {
        long first = contact("adsb", "4D2228", "AZA123", "{}");
        for (int i = 0; i < 8; i++) {
            position(first, "adsb", 45.0 + i, 9.0, BASE_MS + i * 1000L);
        }
        long second = contact("adsb", "AB1234", "XYZ", "{}");
        position(second, "adsb", 50.0, 8.0, BASE_MS + 1000);

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
        long first = contact("adsb", "4D2228", "AZA123", "{}");
        position(first, "adsb", 45.0, 9.0, BASE_MS);
        long second = contact("adsb", "AB1234", "XYZ", "{}");
        position(second, "adsb", 50.0, 8.0, BASE_MS);

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
        long id = contact("adsb", "4D2228", "AZA123", "{}");
        for (int i = 0; i < 11; i++) {
            position(id, "adsb", 45.0, 9.0, BASE_MS + i * 1000L);
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
        long id = contact("adsb", "4D2228", "AZA123", "{}");
        position(id, "adsb", 45.0, 9.0, BASE_MS);
        position(id, "adsb", 45.1, 9.1, BASE_MS + 5000);

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
    @SuppressWarnings("unchecked")
    void contactsWithoutPositionsAreAbsentFromEveryEndpoint() {
        contact("adsb", "AB1234", "XYZ", "{}");

        ResponseEntity<Map> positions = rest.getForEntity(
                "/api/v1/adsb/positions?from_ms={f}&to_ms={t}", Map.class, BASE_MS, BASE_MS + 10_000);
        assertThat((List<Map<String, Object>>) positions.getBody().get("positions")).isEmpty();

        ResponseEntity<Map> tracks = rest.getForEntity(
                "/api/v1/adsb/tracks?from_ms={f}&to_ms={t}", Map.class, BASE_MS, BASE_MS + 10_000);
        assertThat((List<Map<String, Object>>) tracks.getBody().get("tracks")).isEmpty();

        assertThat(rest.getForEntity("/api/v1/adsb/range", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
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
