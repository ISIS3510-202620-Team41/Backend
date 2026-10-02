package com.group41.backend.schedule.repository;

import com.group41.backend.schedule.domain.ScheduleSource;
import com.group41.backend.schedule.domain.TimeBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TimeBlockRepository extends JpaRepository<TimeBlock, UUID> {

    /** Bloques del usuario que tocan el rango [from, to), aunque empiecen antes o terminen despues. */
    @Query("""
            select t from TimeBlock t
            where t.userId = :userId
              and t.startTime < :to
              and t.endTime > :from
            order by t.startTime
            """)
    List<TimeBlock> findOverlapping(@Param("userId") UUID userId,
                                    @Param("from") ZonedDateTime from,
                                    @Param("to") ZonedDateTime to);

    /** De esos usuarios, los que tienen un bloque activo en ese instante (inicio inclusivo, fin exclusivo). */
    @Query("""
            select distinct t.userId from TimeBlock t
            where t.userId in :userIds
              and t.startTime <= :at
              and t.endTime > :at
            """)
    List<UUID> findBusyUserIds(@Param("userIds") Collection<UUID> userIds,
                               @Param("at") ZonedDateTime at);

    /** Borra los bloques de un origen (ej. ICS) para reemplazarlos por una importacion nueva. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TimeBlock t where t.userId = :userId and t.source = :source")
    void deleteByUserIdAndSource(@Param("userId") UUID userId, @Param("source") ScheduleSource source);
}