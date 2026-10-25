package com.uav.dispatch.mapper;
import com.uav.dispatch.pojo.entity.DispatchMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DispatchMaterialRepository extends JpaRepository<DispatchMaterial, String> {
    List<DispatchMaterial> findByOrderIdOrderByCreatedAtAsc(String orderId);
}
