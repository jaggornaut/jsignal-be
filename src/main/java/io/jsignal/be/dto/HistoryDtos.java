package io.jsignal.be.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class HistoryDtos {

    private HistoryDtos() {
    }

    public record DomainsResponse(List<DomainInfo> domains) {
    }

    public record DomainInfo(String domain, long points) {
    }

    public record RangeResponse(
            @JsonProperty("from_ms") long fromMs,
            @JsonProperty("to_ms") long toMs) {
    }

    public record PositionsResponse(List<Position> positions) {
    }

    public record Position(
            @JsonProperty("track_key") String trackKey,
            String label,
            double lat,
            double lon,
            Integer alt,
            @JsonProperty("speed_kts") Float speedKts,
            @JsonProperty("heading_deg") Float headingDeg,
            @JsonProperty("ts_ms") long tsMs) {
    }

    public record TracksResponse(List<Track> tracks) {
    }

    public record Track(
            @JsonProperty("track_key") String trackKey,
            String label,
            long points,
            @JsonProperty("first_ms") long firstMs,
            @JsonProperty("last_ms") long lastMs,
            Map<String, Object> attrs) {
    }

    public record ErrorResponse(String error) {
    }
}
