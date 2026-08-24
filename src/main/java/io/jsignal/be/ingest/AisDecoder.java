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
public class AisDecoder implements DomainDecoder {

    private static final Logger log = LoggerFactory.getLogger(AisDecoder.class);

    private static final String[] LABEL_FIELDS = {"vessel_name", "call_sign"};

    private final ObjectMapper mapper;

    public AisDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String domainPrefix() {
        return "ais";
    }

    @Override
    public Optional<TrackPoint> decode(String topic, byte[] payload, Instant receivedAt) {
        try {
            String json = new String(payload, StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                log.debug("Non-object payload on topic {}, discarding", topic);
                return Optional.empty();
            }

            String trackKey = PayloadJson.text(node.get("mmsi"));
            if (trackKey == null) {
                log.debug("Missing or empty mmsi on topic {}, discarding", topic);
                return Optional.empty();
            }

            String label = PayloadJson.text(PayloadJson.firstPresent(node, LABEL_FIELDS));
            Double lat = PayloadJson.parseDouble(node.get("latitude"));
            Double lon = PayloadJson.parseDouble(node.get("longitude"));
            Float speedKts = PayloadJson.toFloat(PayloadJson.parseDouble(node.get("sog_kts")));
            Float courseDeg = PayloadJson.toFloat(PayloadJson.parseDouble(node.get("cog_deg")));

            Double epochMs = PayloadJson.parseDouble(node.get("last_seen_ms"));
            Instant ts = epochMs != null ? Instant.ofEpochMilli(epochMs.longValue()) : receivedAt;

            return Optional.of(new TrackPoint(domainPrefix(), trackKey, label, lat, lon,
                    null, speedKts, courseDeg, ts, topic, json));
        } catch (Exception e) {
            log.debug("Failed to decode payload on topic {}: {}", topic, e.toString());
            return Optional.empty();
        }
    }
}
