package io.jsignal.be.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqttIngestServiceTest {

    @Test
    void domainPrefixIsTheFirstTopicSegment() {
        assertThat(MqttIngestService.domainPrefix("adsb/aircraft/4D2228")).isEqualTo("adsb");
        assertThat(MqttIngestService.domainPrefix("ais/vessel/1")).isEqualTo("ais");
        assertThat(MqttIngestService.domainPrefix("adsb")).isEqualTo("adsb");
    }
}
