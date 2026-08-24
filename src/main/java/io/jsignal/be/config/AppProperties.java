package io.jsignal.be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue Mqtt mqtt,
        @DefaultValue("200000") int positionsMaxRows,
        Integer retentionDays) {

    public record Mqtt(
            @DefaultValue("127.0.0.1") String host,
            @DefaultValue("1883") int port,
            @DefaultValue({"adsb/#", "ais/#"}) List<String> topics,
            @DefaultValue("true") boolean enabled) {

        public String brokerUrl() {
            return "tcp://" + host + ":" + port;
        }
    }
}
