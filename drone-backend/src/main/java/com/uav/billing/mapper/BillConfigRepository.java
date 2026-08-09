package com.uav.billing.mapper;

import com.uav.billing.pojo.entity.BillConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillConfigRepository extends JpaRepository<BillConfig, Long> {

    Optional<BillConfig> findByConfigKey(String configKey);

    List<BillConfig> findByEnabledTrue();
}
