package com.group41.backend.user.repository;

import com.group41.backend.activity.domain.Category;
import com.group41.backend.user.domain.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, UUID> {

    List<UserPreference> findByUserId(UUID userId);

    Optional<UserPreference> findByUserIdAndCategory(UUID userId, Category category);
}