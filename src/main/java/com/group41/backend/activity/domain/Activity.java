package com.group41.backend.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/** Un evento al que los usuarios pueden unirse, con ubicacion y horario. */
@Entity
@Table(
        name = "activities",
        indexes = {
                @Index(name = "idx_activity_location", columnList = "latitude, longitude"),
                @Index(name = "idx_activity_end", columnList = "end_time")
        })
public class Activity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Column(name = "start_time", nullable = false)
    private ZonedDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private ZonedDateTime endTime;

    @Column(name = "location_name", nullable = false, length = 200)
    private String locationName;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** Quien la creo. Es null en las actividades del seed. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(length = 50)
    private String price;

    @Column
    private Integer capacity;

    @Column(name = "about_location", length = 2000)
    private String aboutLocation;

    protected Activity() {
        // Requerido por JPA.
    }

    public Activity(String title, String description, Category category,
                    ZonedDateTime startTime, ZonedDateTime endTime,
                    String locationName, double latitude, double longitude, UUID createdBy) {
        this(title, description, category, startTime, endTime, locationName, latitude, longitude, createdBy,
                null, null, null, null);
    }

    public Activity(String title, String description, Category category,
                    ZonedDateTime startTime, ZonedDateTime endTime,
                    String locationName, double latitude, double longitude, UUID createdBy,
                    String imageUrl, String price, Integer capacity, String aboutLocation) {
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("El fin de la actividad debe ser posterior al inicio");
        }
        this.title = title;
        this.description = description;
        this.category = category;
        this.startTime = startTime;
        this.endTime = endTime;
        this.locationName = locationName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.createdBy = createdBy;
        this.imageUrl = imageUrl;
        this.price = price;
        this.capacity = capacity;
        this.aboutLocation = aboutLocation;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }

    public String getLocationName() {
        return locationName;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public String getAboutLocation() {
        return aboutLocation;
    }

    public void setAboutLocation(String aboutLocation) {
        this.aboutLocation = aboutLocation;
    }
}
