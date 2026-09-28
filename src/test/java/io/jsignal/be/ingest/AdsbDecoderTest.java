package io.jsignal.be.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class AdsbDecoderTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-06-12T12:00:00Z");
    private static final String TOPIC = "adsb/aircraft/4D2228";
    private static final float TOLERANCE = 0.01f;

    private final AdsbDecoder decoder = new AdsbDecoder(new ObjectMapper());

    private Optional<ContactSnapshot> decode(String json) {
        return decoder.decode(TOPIC, json.getBytes(StandardCharsets.UTF_8), RECEIVED_AT);
    }

    @Test
    void decodesReferencePayloadWithStringAndNumericValues() {
        ContactSnapshot s = decode("""
                {
                  "icao": "4D2228",
                  "last_seen_ms": 1718000000000,
                  "callsign": "AZA123",
                  "ground_speed_kts": "450.25",
                  "heading_deg": "270.50",
                  "vertical_rate_fpm": -64,
                  "altitude_barometric_ft": 36000,
                  "latitude": "45.12345",
                  "longitude": "9.12345",
                  "altitude_gps": 36100
                }
                """).orElseThrow();

        assertThat(s.domain()).isEqualTo("adsb");
        assertThat(s.identifier()).isEqualTo("4D2228");
        assertThat(s.name()).isEqualTo("AZA123");
        assertThat(s.lat()).isEqualTo(45.12345);
        assertThat(s.lon()).isEqualTo(9.12345);
        assertThat(s.speedMps()).isCloseTo(231.62842f, offset(TOLERANCE));
        assertThat(s.altM()).isCloseTo(10972.8f, offset(TOLERANCE));
        assertThat(s.ts()).isEqualTo(Instant.ofEpochMilli(1718000000000L));
        assertThat(s.topic()).isEqualTo(TOPIC);
    }

    @Test
    void headingFieldIsGroundTrackAndLandsInCourseNotHeading() {
        ContactSnapshot s = decode("""
                {"icao": "4D2228", "latitude": 45.1, "longitude": 9.1, "heading_deg": 270.5}
                """).orElseThrow();

        assertThat(s.courseDeg()).isEqualTo(270.5f);
        assertThat(s.headingDeg()).isNull();
    }

    @Test
    void numericFieldsAcceptBothStringsAndNumbers() {
        ContactSnapshot asNumbers = decode("""
                {"icao": "A", "latitude": 45.5, "longitude": 9.5,
                 "ground_speed_kts": 450.25, "heading_deg": 270.5, "altitude_barometric_ft": 36000}
                """).orElseThrow();
        ContactSnapshot asStrings = decode("""
                {"icao": "A", "latitude": "45.5", "longitude": "9.5",
                 "ground_speed_kts": "450.25", "heading_deg": "270.5", "altitude_barometric_ft": "36000"}
                """).orElseThrow();

        assertThat(asStrings.lat()).isEqualTo(asNumbers.lat()).isEqualTo(45.5);
        assertThat(asStrings.lon()).isEqualTo(asNumbers.lon()).isEqualTo(9.5);
        assertThat(asStrings.speedMps()).isEqualTo(asNumbers.speedMps());
        assertThat(asStrings.courseDeg()).isEqualTo(asNumbers.courseDeg()).isEqualTo(270.5f);
        assertThat(asStrings.altM()).isEqualTo(asNumbers.altM());
    }

    @ParameterizedTest
    @ValueSource(strings = {"altitude_barometric_ft", "altitude_barometric", "altitude_gps"})
    void altitudeIsReadFromEveryFieldTheModulePublishesAndConvertedToMetres(String field) {
        ContactSnapshot s = decode("{\"icao\": \"A\", \"%s\": 12000}".formatted(field)).orElseThrow();
        assertThat(s.altM()).isCloseTo(3657.6f, offset(TOLERANCE));
    }

    @Test
    void barometricAltitudeWinsOverGpsAltitude() {
        ContactSnapshot s = decode("""
                {"icao": "A", "altitude_barometric_ft": 36000, "altitude_gps": 36100}
                """).orElseThrow();
        assertThat(s.altM()).isCloseTo(10972.8f, offset(TOLERANCE));
    }

    @Test
    void timestampFallsBackToReceptionTimeWhenMissing() {
        assertThat(decode("{\"icao\": \"A\"}").orElseThrow().ts()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void missingIcaoIsDiscarded() {
        assertThat(decode("{\"latitude\": 45.0, \"longitude\": 9.0}")).isEmpty();
    }

    @Test
    void emptyIcaoIsDiscarded() {
        assertThat(decode("{\"icao\": \"\"}")).isEmpty();
        assertThat(decode("{\"icao\": \"   \"}")).isEmpty();
    }

    @Test
    void partialPayloadWithoutLatLonIsAccepted() {
        ContactSnapshot s = decode("{\"icao\": \"4D2228\", \"altitude_barometric_ft\": 35000}")
                .orElseThrow();
        assertThat(s.hasPosition()).isFalse();
        assertThat(s.lat()).isNull();
        assertThat(s.lon()).isNull();
        assertThat(s.altM()).isNotNull();
    }

    @Test
    void callsignIsTrimmedAndBlankBecomesNull() {
        assertThat(decode("{\"icao\": \"A\", \"callsign\": \"  AZA123 \"}").orElseThrow().name())
                .isEqualTo("AZA123");
        assertThat(decode("{\"icao\": \"A\", \"callsign\": \"   \"}").orElseThrow().name()).isNull();
        assertThat(decode("{\"icao\": \"A\"}").orElseThrow().name()).isNull();
    }

    @Test
    void invalidJsonAndNonNumericValuesNeverThrow() {
        assertThat(decode("not json at all")).isEmpty();
        assertThat(decode("[1, 2, 3]")).isEmpty();
        ContactSnapshot s = decode("{\"icao\": \"A\", \"latitude\": \"abc\"}").orElseThrow();
        assertThat(s.lat()).isNull();
    }
}
