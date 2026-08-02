package io.jsignal.be.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsignal.be.entity.TrackPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Component
public class AdsbDecoder implements DomainDecoder {

    private static final Logger log = LoggerFactory.getLogger(AdsbDecoder.class);

    private static final String[] SPEED_FIELDS =
            {"ground_speed_kts", "gs", "speed", "ground_speed_knots", "speed_knots"};
    private static final String[] HEADING_FIELDS =
            {"heading_deg", "heading_degrees", "track", "hdg", "heading"};
    private static final String[] ALT_FIELDS =
            {"altitude_barometric_ft", "altitude_barometric", "altitude_gps", "alt_baro", "alt", "altitude"};
    private static final String[] TS_FIELDS = {"last_seen_ms", "timestamp_ms"};

    private final ObjectMapper mapper;

    public AdsbDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String domainPrefix() {
        return "adsb";
    }

    @Override
    public Optional<TrackPoint> decode(String topic, byte[] payload, Instant receivedAt) {
        try {
            String json = stripLeadingGarbage(new String(payload, StandardCharsets.UTF_8));
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                log.debug("Non-object payload on topic {}, discarding", topic);
                return Optional.empty();
            }

            String trackKey = text(node.get("icao"));
            if (trackKey == null || trackKey.isEmpty()) {
                log.debug("Missing or empty icao on topic {}, discarding", topic);
                return Optional.empty();
            }

            String label = text(node.get("callsign"));
            Double lat = parseDouble(node.get("latitude"));
            Double lon = parseDouble(node.get("longitude"));
            Float speedKts = toFloat(parseDouble(firstPresent(node, SPEED_FIELDS)));
            Float headingDeg = toFloat(parseDouble(firstPresent(node, HEADING_FIELDS)));
            Integer alt = toInt(parseDouble(firstPresent(node, ALT_FIELDS)));

            Double epochMs = parseDouble(firstPresent(node, TS_FIELDS));
            Instant ts = epochMs != null ? Instant.ofEpochMilli(epochMs.longValue()) : receivedAt;

            return Optional.of(new TrackPoint(domainPrefix(), trackKey, label, lat, lon,
                    alt, speedKts, headingDeg, ts, topic, json));
        } catch (Exception e) {
            log.debug("Failed to decode payload on topic {}: {}", topic, e.toString());
            return Optional.empty();
        }
    }

    static Double parseDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            String s = node.asText().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static JsonNode firstPresent(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    static String stripLeadingGarbage(String payload) {
        int brace = payload.indexOf('{');
        return brace > 0 ? payload.substring(brace) : payload;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static Float toFloat(Double d) {
        return d == null ? null : d.floatValue();
    }

    private static Integer toInt(Double d) {
        return d == null ? null : (int) Math.round(d);
    }
}
