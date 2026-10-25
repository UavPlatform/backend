package com.uav.equipment.mapper;
import com.uav.equipment.pojo.entity.ProviderDrone;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ProviderDroneRepository extends JpaRepository<ProviderDrone, String> {
    boolean existsBySerialNumber(String serial);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from ProviderDrone d where d.id = :id")
    Optional<ProviderDrone> lockDrone(@Param("id") String id);
    Page<ProviderDrone> findByProviderId(Long provider, Pageable page);
    @Query("select d from ProviderDrone d, EquipmentProvider p, AircraftModel m where d.providerId = p.id and d.aircraftModelId = m.id and p.enabled = true and d.enabled = true and m.enabled = true and (:provider is null or d.providerId = :provider)")
    Page<ProviderDrone> catalog(@Param("provider") Long provider, Pageable page);
    /** Admin supervision list (ADR-0008): optional provider / airport-name filters. */
    @Query("select d from ProviderDrone d where (:provider is null or d.providerId = :provider)"
            + " and (:airport is null or d.airportName like :airport escape '\\')")
    Page<ProviderDrone> adminSearch(@Param("provider") Long provider, @Param("airport") String airport, Pageable page);
}
