package io.jsignal.be.ingest;

import java.time.Instant;
import java.util.Optional;

public interface DomainDecoder {

    String domainPrefix();

    Optional<ContactSnapshot> decode(String topic, byte[] payload, Instant receivedAt);
}
