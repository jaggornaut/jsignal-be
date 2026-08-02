package io.jsignal.be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "track_points", indexes = {
        @Index(name = "idx_track_points_domain_ts", columnList = "domain, ts"),
        @Index(name = "idx_track_points_domain_key_ts", columnList = "domain, trackKey, ts")
})
public class TrackPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "track_points_id_gen")
    @SequenceGenerator(name = "track_points_id_gen",
            sequenceName = "track_points_id_seq", allocationSize = 50)
    private Long id;

    private String domain;

    private String trackKey;

    private String label;

    private Double lat;

    private Double lon;

    private Integer alt;

    private Float speedKts;

    private Float headingDeg;

    private Instant ts;

    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw")
    private String rawJson;

    protected TrackPoint() {
    }

    public TrackPoint(String domain, String trackKey, String label,
                      Double lat, Double lon, Integer alt,
                      Float speedKts, Float headingDeg,
                      Instant ts, String topic, String rawJson) {
        this.domain = domain;
        this.trackKey = trackKey;
        this.label = label;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.speedKts = speedKts;
        this.headingDeg = headingDeg;
        this.ts = ts;
        this.topic = topic;
        this.rawJson = rawJson;
    }

    public String getDomain() {
        return domain;
    }

    public String getTrackKey() {
        return trackKey;
    }

    public String getLabel() {
        return label;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }

    public Integer getAlt() {
        return alt;
    }

    public Float getSpeedKts() {
        return speedKts;
    }

    public Float getHeadingDeg() {
        return headingDeg;
    }

    public Instant getTs() {
        return ts;
    }

    public String getTopic() {
        return topic;
    }

    public String getRawJson() {
        return rawJson;
    }
}
