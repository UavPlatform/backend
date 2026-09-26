package com.uav.pay.mapper;

import com.uav.pay.pojo.entity.PayRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayRecordRepository extends JpaRepository<PayRecord, Long> {

    Optional<PayRecord> findByOrderNum(String orderNum);

    Optional<PayRecord> findByTransactionId(String transactionId);

    /** 批量按订单号取支付流水（管理端订单列表回显 paidAt，避免逐单查询，TASK-BACKEND-007）。 */
    List<PayRecord> findByOrderNumIn(Collection<String> orderNums);
}
