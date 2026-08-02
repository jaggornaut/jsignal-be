package io.jsignal.be.repository;

import io.jsignal.be.entity.TrackPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {

    @Query("""
            SELECT p.domain AS domain, COUNT(p) AS points
            FROM TrackPoint p
            GROUP BY p.domain
            ORDER BY p.domain
            """)
    List<DomainCount> countByDomain();

    @Query("""
            SELECT MIN(p.ts) AS firstTs, MAX(p.ts) AS lastTs
            FROM TrackPoint p
            WHERE p.domain = :domain AND p.lat IS NOT NULL AND p.lon IS NOT NULL
            """)
    TimeRange findRange(String domain);

    @Query(value = """
            SELECT track_key AS "trackKey", label AS "label", lat AS "lat", lon AS "lon",
                   alt AS "alt", speed_kts AS "speedKts", heading_deg AS "headingDeg",
                   CAST(EXTRACT(EPOCH FROM ts) * 1000 AS BIGINT) AS "tsMs"
            FROM track_points
            WHERE domain = :domain AND ts >= :from AND ts <= :to
              AND lat IS NOT NULL AND lon IS NOT NULL
              AND (CAST(:trackKey AS TEXT) IS NULL OR track_key = CAST(:trackKey AS TEXT))
            ORDER BY ts
            LIMIT :limit
            """, nativeQuery = true)
    List<PositionRow> findPositions(String domain, Instant from, Instant to,
                                    String trackKey, int limit);

    @Query(value = """
            SELECT track_key AS "trackKey", label AS "label", lat AS "lat", lon AS "lon",
                   alt AS "alt", speed_kts AS "speedKts", heading_deg AS "headingDeg",
                   CAST(EXTRACT(EPOCH FROM ts) * 1000 AS BIGINT) AS "tsMs"
            FROM (
                SELECT DISTINCT ON (track_key, bucket) *
                FROM (
                    SELECT track_key, label, lat, lon, alt, speed_kts, heading_deg, ts,
                           FLOOR(EXTRACT(EPOCH FROM ts) / :intervalS) AS bucket
                    FROM track_points
                    WHERE domain = :domain AND ts >= :from AND ts <= :to
                      AND lat IS NOT NULL AND lon IS NOT NULL
                      AND (CAST(:trackKey AS TEXT) IS NULL OR track_key = CAST(:trackKey AS TEXT))
                ) pts
                ORDER BY track_key, bucket, ts
            ) sampled
            ORDER BY ts
            LIMIT :limit
            """, nativeQuery = true)
    List<PositionRow> findPositionsDownsampled(String domain, Instant from, Instant to,
                                               String trackKey, int intervalS, int limit);

    @Query(value = """
            SELECT track_key AS "trackKey",
                   (ARRAY_AGG(label ORDER BY ts DESC) FILTER (WHERE label IS NOT NULL))[1] AS "label",
                   COUNT(*) AS "points",
                   CAST(EXTRACT(EPOCH FROM MIN(ts)) * 1000 AS BIGINT) AS "firstMs",
                   CAST(EXTRACT(EPOCH FROM MAX(ts)) * 1000 AS BIGINT) AS "lastMs"
            FROM track_points
            WHERE domain = :domain AND ts >= :from AND ts <= :to
              AND lat IS NOT NULL AND lon IS NOT NULL
            GROUP BY track_key
            ORDER BY track_key
            """, nativeQuery = true)
    List<TrackRow> findTracks(String domain, Instant from, Instant to);

    @Modifying
    @Query(value = "DELETE FROM track_points WHERE ts < NOW() - make_interval(days => :days)",
            nativeQuery = true)
    int deleteOlderThan(int days);

    interface DomainCount {
        String getDomain();
        long getPoints();
    }

    interface TimeRange {
        Instant getFirstTs();
        Instant getLastTs();
    }

    interface PositionRow {
        String getTrackKey();
        String getLabel();
        Double getLat();
        Double getLon();
        Integer getAlt();
        Float getSpeedKts();
        Float getHeadingDeg();
        Long getTsMs();
    }

    interface TrackRow {
        String getTrackKey();
        String getLabel();
        long getPoints();
        long getFirstMs();
        long getLastMs();
    }
}
