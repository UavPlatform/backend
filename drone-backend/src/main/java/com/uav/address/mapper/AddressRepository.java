package com.uav.address.mapper;

import com.uav.address.pojo.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {

    /** 列表：默认地址在前，其余按创建时间倒序 */
    List<Address> findByUserIdOrderByIsDefaultDescCreateTimeDesc(Long userId);

    Optional<Address> findByIdAndUserId(Long id, Long userId);

    Optional<Address> findByUserIdAndIsDefaultTrue(Long userId);

    /** 设默认前，清掉该用户其他默认地址 */
    @Modifying
    @Query("update Address a set a.isDefault = false where a.userId = :userId and a.isDefault = true and a.id <> :keepId")
    void clearOtherDefaults(@Param("userId") Long userId, @Param("keepId") Long keepId);
}
