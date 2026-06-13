package com.uav.pay.mapper;

import com.uav.pay.pojo.entity.PayRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PayRecordRepository extends JpaRepository<PayRecord, Long> {

    Optional<PayRecord> findByOrderNum(String orderNum);

    Optional<PayRecord> findByTransactionId(String transactionId);
}
