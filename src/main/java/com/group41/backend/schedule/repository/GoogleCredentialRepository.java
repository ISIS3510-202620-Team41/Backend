package com.group41.backend.schedule.repository;

import com.group41.backend.schedule.domain.GoogleCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GoogleCredentialRepository extends JpaRepository<GoogleCredential, UUID> {

    Optional<GoogleCredential> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}