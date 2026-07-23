package com.uav.order.mapper;

import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.server.enums.ComplaintStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderComplaintRepository extends JpaRepository<OrderComplaint, Long> {

    Optional<OrderComplaint> findByOrderNum(String orderNum);

    Page<OrderComplaint> findByStatusOrderByCreateTimeAsc(ComplaintStatus status, Pageable pageable);

    Page<OrderComplaint> findAllByOrderByCreateTimeDesc(Pageable pageable);

    boolean existsByOrderNum(String orderNum);
}
