package com.uav.equipment.mapper;
import com.uav.equipment.pojo.entity.ProviderDrone;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProviderDroneRepository extends JpaRepository<ProviderDrone, String> {
    boolean existsBySerialNumber(String serial);
    boolean existsByAircraftModelId(Long aircraftModelId);
    boolean existsByAirportId(Long airportId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from ProviderDrone d where d.id = :id")
    Optional<ProviderDrone> lockDrone(@Param("id") String id);
    @Query("select d from ProviderDrone d, AircraftModel m where d.aircraftModelId = m.id and d.enabled = true and m.enabled = true")
    Page<ProviderDrone> catalog(Pageable page);
    /** Admin list (REQ-SUP-GOV-005): optional airport-name filter via join; disabled drones included. */
    @Query("""
            select d from ProviderDrone d, Airport a
            where d.airportId = a.id
              and (:airport is null or a.name like :airport escape '\\')
            """)
    Page<ProviderDrone> adminSearch(@Param("airport") String airport, Pageable page);

    @Query("""
            select d from ProviderDrone d
            where d.aircraftModelId = :modelId and d.enabled = true and d.currentBorrowId is null
            order by d.createdAt asc, d.id asc
            """)
    List<ProviderDrone> findInStockByModel(@Param("modelId") Long modelId);

    @Query("""
            select count(d) from ProviderDrone d
            where d.aircraftModelId = :modelId and d.enabled = true and d.currentBorrowId is null
            """)
    long countInStockByModel(@Param("modelId") Long modelId);

    @Query("""
            select d.aircraftModelId, count(d) from ProviderDrone d
            where d.enabled = true and d.currentBorrowId is null
            group by d.aircraftModelId
            """)
    List<Object[]> countInStockGroupedByModel();
}
