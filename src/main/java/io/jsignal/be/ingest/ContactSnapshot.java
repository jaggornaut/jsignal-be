package io.jsignal.be.ingest;

import java.time.Instant;

public record ContactSnapshot(
        String domain,
        String identifier,
        String name,
        Instant ts,
        String topic,
        String rawJson,
        Double lat,
        Double lon,
        Float altM,
        Float speedMps,
        Float courseDeg,
        Float headingDeg) {

    public boolean hasPosition() {
        return lat != null && lon != null;
    }

    public Key key() {
        return new Key(domain, identifier);
    }

    public record Key(String domain, String identifier) {
    }
}
