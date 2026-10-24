package com.uav.equipment.mapper;
import com.uav.equipment.pojo.entity.EquipmentProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface EquipmentProviderRepository extends JpaRepository<EquipmentProvider, Long> {
    Optional<EquipmentProvider> findByUsername(String username);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from EquipmentProvider p where p.id = :id")
    Optional<EquipmentProvider> lockProvider(@Param("id") Long id);
}
