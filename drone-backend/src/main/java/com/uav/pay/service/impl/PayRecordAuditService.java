package com.uav.pay.service.impl;

import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付流水审计写入（独立事务）。
 *
 * <p>handleNotify 在拒绝入账（金额不符/订单缺失）时抛出 PayNotifyException 会回滚主事务，
 * 若错误信息随主事务写入会被一并回滚。本服务以 REQUIRES_NEW 在独立事务中落库审计信息，
 * 保证「拒绝入账并记录」的留痕在回滚后依然存在。
 */
@Service
@Slf4j
public class PayRecordAuditService {

    private final PayRecordRepository payRecordRepository;

    public PayRecordAuditService(PayRecordRepository payRecordRepository) {
        this.payRecordRepository = payRecordRepository;
    }

    /**
     * 独立事务写入错误信息（不影响外层事务的提交/回滚状态）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeRecordError(Long recordId, String errorMsg) {
        payRecordRepository.findById(recordId).ifPresent(record -> {
            record.setErrorMsg(errorMsg);
            payRecordRepository.save(record);
            log.warn("[支付审计] 流水 {} 记录错误信息: {}", record.getOrderNum(), errorMsg);
        });
    }
}
