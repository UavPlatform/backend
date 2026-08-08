package com.uav.user.mapper;

import com.uav.user.pojo.entity.RiderUav;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiderUavRepository extends JpaRepository<RiderUav, Long> {

    List<RiderUav> findByUserId(Long userId);

    boolean existsByDjiId(String djiId);

    int deleteByUserIdAndDjiId(Long userId, String djiId);

    boolean existsByUserId(Long userId);
}
