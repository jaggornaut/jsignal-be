package io.jsignal.be.ingest;

import io.jsignal.be.entity.TrackPoint;

import java.time.Instant;
import java.util.Optional;

public interface DomainDecoder {

    String domainPrefix();

    Optional<TrackPoint> decode(String topic, byte[] payload, Instant receivedAt);
}
