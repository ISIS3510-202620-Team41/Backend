package com.group41.backend.activity.repository;

import com.group41.backend.activity.domain.UserActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserActivityRepository extends JpaRepository<UserActivity, UUID> {

    interface ParticipantCount {
        UUID getActivityId();

        Long getTotal();
    }

    boolean existsByUserIdAndActivityId(UUID userId, UUID activityId);

    Optional<UserActivity> findByUserIdAndActivityId(UUID userId, UUID activityId);

    long countByActivityId(UUID activityId);

    /** Inscripciones recientes: la base del calculo de preferencias. */
    List<UserActivity> findByUserIdAndJoinedAtAfter(UUID userId, Instant since);

    @Query("select ua.activityId from UserActivity ua where ua.userId = :userId")
    List<UUID> findActivityIdsByUserId(@Param("userId") UUID userId);

    @Query("""
            select ua.activityId as activityId, count(ua) as total
            from UserActivity ua
            where ua.activityId in :ids
            group by ua.activityId
            """)
    List<ParticipantCount> countByActivityIds(@Param("ids") Collection<UUID> ids);
}