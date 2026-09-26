package com.uav.user.mapper;

import com.uav.user.pojo.entity.RiderUav;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiderUavRepository extends JpaRepository<RiderUav, Long> {

    List<RiderUav> findByUserId(Long userId);

    Optional<RiderUav> findByUserIdAndDjiId(Long userId, String djiId);

    boolean existsByDjiId(String djiId);

    boolean existsByUserIdAndDjiId(Long userId, String djiId);

    int deleteByUserIdAndDjiId(Long userId, String djiId);

    boolean existsByUserId(Long userId);
}
