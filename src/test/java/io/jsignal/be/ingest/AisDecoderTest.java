package io.jsignal.be.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsignal.be.entity.TrackPoint;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AisDecoderTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-06T12:00:00Z");
    private static final String TOPIC = "ais/vessel/247374900";

    private final AisDecoder decoder = new AisDecoder(new ObjectMapper());

    private Optional<TrackPoint> decode(String json) {
        return decoder.decode(TOPIC, json.getBytes(StandardCharsets.UTF_8), RECEIVED_AT);
    }

    @Test
    void decodesPayloadPublishedByAisModule() {
        TrackPoint p = decode("""
                {
                  "mmsi": "247374900",
                  "vessel_name": "MT.MITCHELL",
                  "call_sign": "WDA9674",
                  "latitude": 45.4321,
                  "longitude": 12.3456,
                  "sog_kts": 12.3,
                  "cog_deg": 245.1,
                  "heading_deg": 243,
                  "nav_status": "under way using engine",
                  "ship_type": "cargo",
                  "destination": "VENEZIA",
                  "draught_m": 6.0,
                  "imo": 6710932,
                  "last_seen_ms": 1786032112570
                }
                """).orElseThrow();

        assertThat(p.getDomain()).isEqualTo("ais");
        assertThat(p.getTrackKey()).isEqualTo("247374900");
        assertThat(p.getLabel()).isEqualTo("MT.MITCHELL");
        assertThat(p.getLat()).isEqualTo(45.4321);
        assertThat(p.getLon()).isEqualTo(12.3456);
        assertThat(p.getSpeedKts()).isEqualTo(12.3f);
        assertThat(p.getTs()).isEqualTo(Instant.ofEpochMilli(1786032112570L));
        assertThat(p.getTopic()).isEqualTo(TOPIC);
    }

    @Test
    void headingColumnCarriesCourseOverGroundNotTrueHeading() {
        TrackPoint p = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3,
                 "cog_deg": 245.1, "heading_deg": 243}
                """).orElseThrow();

        assertThat(p.getHeadingDeg()).isEqualTo(245.1f);
    }

    @Test
    void altitudeIsAlwaysNullForVessels() {
        TrackPoint p = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3}
                """).orElseThrow();

        assertThat(p.getAlt()).isNull();
    }

    @Test
    void keepsWholePayloadInRawSoDomainSpecificFieldsSurvive() {
        TrackPoint p = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3,
                 "nav_status": "at anchor", "destination": "VENEZIA", "draught_m": 6.0}
                """).orElseThrow();

        assertThat(p.getRawJson())
                .contains("\"nav_status\"")
                .contains("\"destination\"")
                .contains("\"draught_m\"");
    }

    @Test
    void baseStationReportsHaveNoNameAndNoSpeed() {
        TrackPoint p = decode("""
                {"mmsi": "002470137", "base_station": true,
                 "latitude": 45.451815, "longitude": 12.254981666666668}
                """).orElseThrow();

        assertThat(p.getTrackKey()).isEqualTo("002470137");
        assertThat(p.getLabel()).isNull();
        assertThat(p.getSpeedKts()).isNull();
        assertThat(p.getHeadingDeg()).isNull();
    }

    @Test
    void fallsBackToReceptionTimeWhenTimestampIsMissing() {
        TrackPoint p = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3}
                """).orElseThrow();

        assertThat(p.getTs()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void discardsPayloadWithoutMmsi() {
        assertThat(decode("{\"latitude\": 45.4, \"longitude\": 12.3}")).isEmpty();
    }

    @Test
    void discardsPayloadThatIsNotAnObject() {
        assertThat(decode("[1, 2, 3]")).isEmpty();
    }
}
