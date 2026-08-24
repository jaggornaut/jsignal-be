package io.jsignal.be.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsignal.be.config.AppProperties;
import io.jsignal.be.dto.HistoryDtos.DomainInfo;
import io.jsignal.be.dto.HistoryDtos.Position;
import io.jsignal.be.dto.HistoryDtos.RangeResponse;
import io.jsignal.be.dto.HistoryDtos.Track;
import io.jsignal.be.repository.TrackPointRepository;
import io.jsignal.be.repository.TrackPointRepository.PositionRow;
import io.jsignal.be.repository.TrackPointRepository.TimeRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class HistoryService {

    private final TrackPointRepository repository;
    private final ObjectMapper mapper;
    private final int maxRows;

    public HistoryService(TrackPointRepository repository, ObjectMapper mapper,
                          AppProperties properties) {
        this.repository = repository;
        this.mapper = mapper;
        this.maxRows = properties.positionsMaxRows();
    }

    public List<DomainInfo> domains() {
        return repository.countByDomain().stream()
                .map(row -> new DomainInfo(row.getDomain(), row.getPoints()))
                .toList();
    }

    public Optional<RangeResponse> range(String domain) {
        TimeRange range = repository.findRange(domain);
        if (range.getFirstTs() == null || range.getLastTs() == null) {
            return Optional.empty();
        }
        return Optional.of(new RangeResponse(
                range.getFirstTs().toEpochMilli(), range.getLastTs().toEpochMilli()));
    }

    public List<Position> positions(String domain, long fromMs, long toMs,
                                    String trackKey, Integer intervalS) {
        validateRange(fromMs, toMs);
        if (intervalS != null && intervalS <= 0) {
            throw new IllegalArgumentException("interval_s must be a positive integer");
        }
        Instant from = Instant.ofEpochMilli(fromMs);
        Instant to = Instant.ofEpochMilli(toMs);

        List<PositionRow> rows = intervalS != null
                ? repository.findPositionsDownsampled(domain, from, to, trackKey, intervalS, maxRows + 1)
                : repository.findPositions(domain, from, to, trackKey, maxRows + 1);
        if (rows.size() > maxRows) {
            throw new TooManyRowsException();
        }
        return rows.stream()
                .map(row -> new Position(row.getTrackKey(), row.getLabel(),
                        row.getLat(), row.getLon(), row.getAlt(),
                        row.getSpeedKts(), row.getHeadingDeg(), row.getTsMs()))
                .toList();
    }

    public List<Track> tracks(String domain, long fromMs, long toMs) {
        validateRange(fromMs, toMs);
        return repository.findTracks(domain,
                        Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)).stream()
                .map(row -> new Track(row.getTrackKey(), row.getLabel(),
                        row.getPoints(), row.getFirstMs(), row.getLastMs(),
                        parseAttrs(row.getRaw())))
                .toList();
    }

    private Map<String, Object> parseAttrs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed =
                    mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void validateRange(long fromMs, long toMs) {
        if (fromMs >= toMs) {
            throw new IllegalArgumentException("from_ms must be < to_ms");
        }
    }

    public static class TooManyRowsException extends RuntimeException {
        TooManyRowsException() {
            super("too many rows, increase interval_s");
        }
    }
}
