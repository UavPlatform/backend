package com.uav.order.mapper;

import com.uav.order.pojo.entity.OrderReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderReviewRepository extends JpaRepository<OrderReview, Long> {

    Optional<OrderReview> findByOrderNum(String orderNum);

    Page<OrderReview> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);

    boolean existsByOrderNum(String orderNum);
}
