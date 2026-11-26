
package com.group41.backend.activity.repository;

import com.group41.backend.activity.domain.UserActivity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.ZonedDateTime;
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

    /** IDs de inscripciones del usuario limitados a las actividades de la página. */
    @Query("""
            select ua.activityId
            from UserActivity ua
            where ua.userId = :userId
              and ua.activityId in :activityIds
            """)
    List<UUID> findJoinedActivityIds(
            @Param("userId") UUID userId,
            @Param("activityIds") Collection<UUID> activityIds
    );

    @Query("""
            select ua.activityId as activityId, count(ua) as total
            from UserActivity ua
            where ua.activityId in :ids
            group by ua.activityId
            """)
    List<ParticipantCount> countByActivityIds(@Param("ids") Collection<UUID> ids);

    /**
     * Recupera las inscripciones de un usuario junto con sus actividades.
     * Evita consultas individuales por cada actividad.
     */
    @Query(
            value = """
                    select new com.group41.backend.activity.repository.JoinedActivityRow(
                        a, ua.id, ua.joinedAt
                    )
                    from UserActivity ua, Activity a
                    where ua.activityId = a.id
                      and ua.userId = :userId
                    order by
                        case when a.startTime >= :now then 0 else 1 end,
                        a.startTime asc,
                        a.id asc
                    """,
            countQuery = """
                    select count(ua)
                    from UserActivity ua
                    where ua.userId = :userId
                    """
    )
    Page<JoinedActivityRow> findJoinedActivities(
            @Param("userId") UUID userId,
            @Param("now") ZonedDateTime now,
            Pageable pageable
    );
}
