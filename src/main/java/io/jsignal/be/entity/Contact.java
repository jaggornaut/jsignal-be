package io.jsignal.be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "contact",
        uniqueConstraints = @UniqueConstraint(name = "uk_contact_domain_identifier",
                columnNames = {"domain", "identifier"}),
        indexes = @Index(name = "idx_contact_last_seen", columnList = "last_seen DESC"))
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain", columnDefinition = "text", nullable = false)
    private String domain;

    @Column(name = "identifier", columnDefinition = "text", nullable = false)
    private String identifier;

    @Column(name = "name", columnDefinition = "text")
    private String name;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private String details;

    protected Contact() {
    }

    public Contact(String domain, String identifier, String name,
                   Instant firstSeen, Instant lastSeen, String details) {
        this.domain = domain;
        this.identifier = identifier;
        this.name = name;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.details = details;
    }

    public void observed(String observedName, String observedDetails, Instant from, Instant to) {
        if (observedName != null) {
            this.name = observedName;
        }
        if (observedDetails != null) {
            this.details = observedDetails;
        }
        if (from.isBefore(firstSeen)) {
            this.firstSeen = from;
        }
        if (to.isAfter(lastSeen)) {
            this.lastSeen = to;
        }
    }

    public Long getId() {
        return id;
    }

    public String getDomain() {
        return domain;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getName() {
        return name;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public String getDetails() {
        return details;
    }
}
