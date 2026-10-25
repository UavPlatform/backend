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
    @Query("select d from ProviderDrone d, AircraftModel m where d.aircraftModelId = m.id and d.enabled = true and m.enabled = true")
    Page<ProviderDrone> catalog(Pageable page);
    /** Admin list (REQ-SUP-GOV-005): optional airport-name filter, disabled drones included. */
    @Query("select d from ProviderDrone d where (:airport is null or d.airportName like :airport escape '\\')")
    Page<ProviderDrone> adminSearch(@Param("airport") String airport, Pageable page);
}
