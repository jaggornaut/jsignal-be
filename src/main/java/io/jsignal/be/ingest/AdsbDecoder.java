package io.jsignal.be.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsignal.be.Units;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Component
public class AdsbDecoder implements DomainDecoder {

    private static final Logger log = LoggerFactory.getLogger(AdsbDecoder.class);

    private static final String[] ALT_FIELDS =
            {"altitude_barometric_ft", "altitude_barometric", "altitude_gps"};

    private final ObjectMapper mapper;

    public AdsbDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String domainPrefix() {
        return "adsb";
    }

    @Override
    public Optional<ContactSnapshot> decode(String topic, byte[] payload, Instant receivedAt) {
        try {
            String json = new String(payload, StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                log.debug("Non-object payload on topic {}, discarding", topic);
                return Optional.empty();
            }

            String identifier = PayloadJson.text(node.get("icao"));
            if (identifier == null) {
                log.debug("Missing or empty icao on topic {}, discarding", topic);
                return Optional.empty();
            }

            String name = PayloadJson.text(node.get("callsign"));
            Double lat = PayloadJson.parseDouble(node.get("latitude"));
            Double lon = PayloadJson.parseDouble(node.get("longitude"));
            Float speedMps =
                    Units.knotsToMps(PayloadJson.parseDouble(node.get("ground_speed_kts")));
            Float courseDeg = PayloadJson.toFloat(PayloadJson.parseDouble(node.get("heading_deg")));
            Float altM = Units.feetToMetres(
                    PayloadJson.parseDouble(PayloadJson.firstPresent(node, ALT_FIELDS)));

            Double epochMs = PayloadJson.parseDouble(node.get("last_seen_ms"));
            Instant ts = epochMs != null ? Instant.ofEpochMilli(epochMs.longValue()) : receivedAt;

            return Optional.of(new ContactSnapshot(domainPrefix(), identifier, name, ts, topic,
                    json, lat, lon, altM, speedMps, courseDeg, null));
        } catch (Exception e) {
            log.debug("Failed to decode payload on topic {}: {}", topic, e.toString());
            return Optional.empty();
        }
    }
}
