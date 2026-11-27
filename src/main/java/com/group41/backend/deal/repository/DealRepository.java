package com.group41.backend.deal.repository;

import com.group41.backend.deal.domain.Deal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public interface DealRepository extends JpaRepository<Deal, UUID> {

    /**
     * Los cupones vigentes, del mayor descuento al menor.
     *
     * La condicion de `validUntil` es nula o futura: un cupon sin fecha no vence, y sin
     * esa rama desapareceria del catalogo por un NULL en la comparacion.
     */
    @Query("""
            select d from Deal d
            where d.validUntil is null or d.validUntil > :now
            order by d.discountPercent desc
            """)
    List<Deal> findActive(@Param("now") ZonedDateTime now);

    boolean existsByValidUntilAfter(ZonedDateTime now);
}
