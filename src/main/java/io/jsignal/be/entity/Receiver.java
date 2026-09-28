package io.jsignal.be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "receiver")
public class Receiver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(name = "name", columnDefinition = "text", nullable = false, unique = true)
    private String name;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lon")
    private Double lon;

    @Column(name = "alt_m")
    private Float altM;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    protected Receiver() {
    }

    public Receiver(String name) {
        this.name = name;
    }

    public Short getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }

    public Float getAltM() {
        return altM;
    }

    public boolean isActive() {
        return active;
    }

    public String getNotes() {
        return notes;
    }
}
