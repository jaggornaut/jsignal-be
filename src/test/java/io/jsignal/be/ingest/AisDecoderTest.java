package io.jsignal.be.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class AisDecoderTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-06T12:00:00Z");
    private static final String TOPIC = "ais/vessel/247374900";

    private final AisDecoder decoder = new AisDecoder(new ObjectMapper());

    private Optional<ContactSnapshot> decode(String json) {
        return decoder.decode(TOPIC, json.getBytes(StandardCharsets.UTF_8), RECEIVED_AT);
    }

    @Test
    void decodesPayloadPublishedByAisModule() {
        ContactSnapshot s = decode("""
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

        assertThat(s.domain()).isEqualTo("ais");
        assertThat(s.identifier()).isEqualTo("247374900");
        assertThat(s.name()).isEqualTo("MT.MITCHELL");
        assertThat(s.lat()).isEqualTo(45.4321);
        assertThat(s.lon()).isEqualTo(12.3456);
        assertThat(s.speedMps()).isCloseTo(6.3277f, offset(0.001f));
        assertThat(s.ts()).isEqualTo(Instant.ofEpochMilli(1786032112570L));
        assertThat(s.topic()).isEqualTo(TOPIC);
    }

    @Test
    void courseCarriesCourseOverGroundAndHeadingCarriesTrueHeading() {
        ContactSnapshot s = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3,
                 "cog_deg": 245.1, "heading_deg": 243}
                """).orElseThrow();

        assertThat(s.courseDeg()).isEqualTo(245.1f);
        assertThat(s.headingDeg()).isEqualTo(243.0f);
    }

    @Test
    void trueHeadingIsNullWhenTheModuleDoesNotPublishIt() {
        ContactSnapshot s = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3, "cog_deg": 245.1}
                """).orElseThrow();

        assertThat(s.courseDeg()).isEqualTo(245.1f);
        assertThat(s.headingDeg()).isNull();
    }

    @Test
    void outOfRangeTrueHeadingIsRejected() {
        assertThat(decode("""
                {"mmsi": "1", "latitude": 45.4, "longitude": 12.3, "heading_deg": 511}
                """).orElseThrow().headingDeg()).isNull();
        assertThat(decode("""
                {"mmsi": "1", "latitude": 45.4, "longitude": 12.3, "heading_deg": 360}
                """).orElseThrow().headingDeg()).isNull();
        assertThat(decode("""
                {"mmsi": "1", "latitude": 45.4, "longitude": 12.3, "heading_deg": 359}
                """).orElseThrow().headingDeg()).isEqualTo(359.0f);
    }

    @Test
    void altitudeIsAlwaysNullForVessels() {
        ContactSnapshot s = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3}
                """).orElseThrow();

        assertThat(s.altM()).isNull();
    }

    @Test
    void keepsWholePayloadInRawSoDomainSpecificFieldsSurvive() {
        ContactSnapshot s = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3,
                 "nav_status": "at anchor", "destination": "VENEZIA", "draught_m": 6.0}
                """).orElseThrow();

        assertThat(s.rawJson())
                .contains("\"nav_status\"")
                .contains("\"destination\"")
                .contains("\"draught_m\"");
    }

    @Test
    void baseStationReportsHaveNoNameAndNoSpeed() {
        ContactSnapshot s = decode("""
                {"mmsi": "002470137", "base_station": true,
                 "latitude": 45.451815, "longitude": 12.254981666666668}
                """).orElseThrow();

        assertThat(s.identifier()).isEqualTo("002470137");
        assertThat(s.name()).isNull();
        assertThat(s.speedMps()).isNull();
        assertThat(s.courseDeg()).isNull();
        assertThat(s.headingDeg()).isNull();
    }

    @Test
    void fallsBackToReceptionTimeWhenTimestampIsMissing() {
        ContactSnapshot s = decode("""
                {"mmsi": "247374900", "latitude": 45.4, "longitude": 12.3}
                """).orElseThrow();

        assertThat(s.ts()).isEqualTo(RECEIVED_AT);
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
