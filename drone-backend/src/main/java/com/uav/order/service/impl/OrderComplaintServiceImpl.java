package com.uav.order.service.impl;

import com.uav.order.mapper.OrderComplaintRepository;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.order.service.OrderComplaintService;
import com.uav.pay.service.WeChatPayService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.ComplaintReason;
import com.uav.server.enums.ComplaintStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderComplaintServiceImpl implements OrderComplaintService {

    private final OrderComplaintRepository complaintRepository;
    private final OrderRepository orderRepository;
    private final WeChatPayService weChatPayService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitComplaint(String orderNum, Long userId, ComplaintReason reason, String description) {
        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getOrderStatus() != OrderStatus.DISPUTED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅争议中的订单可提交投诉，当前状态: " + order.getOrderStatus().getDesc());
        }
        if (complaintRepository.existsByOrderNum(orderNum)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "该订单已有在处理的投诉");
        }

        OrderComplaint complaint = new OrderComplaint();
        complaint.setOrderNum(orderNum);
        complaint.setUserId(userId);
        complaint.setReason(reason);
        complaint.setDescription(description);
        complaint.setRefundAmount(order.getTotalAmount());
        complaintRepository.save(complaint);

        log.info("用户 {} 对订单 {} 提交投诉，原因: {}", userId, orderNum, reason.getDesc());
    }

    @Override
    public OrderComplaint getComplaint(String orderNum, Long userId) {
        OrderComplaint complaint = complaintRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "该订单无投诉记录"));
        if (!complaint.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }
        return complaint;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelComplaint(Long complaintId, Long userId) {
        OrderComplaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "投诉不存在"));
        if (!complaint.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND);
        }
        if (complaint.getStatus() != ComplaintStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "仅待处理状态的投诉可取消");
        }

        orderRepository.findByOrderNum(complaint.getOrderNum()).ifPresent(order -> {
            order.setOrderStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);
        });
        complaintRepository.delete(complaint);

        log.info("用户 {} 取消投诉，订单 {} 恢复已完成", userId, complaint.getOrderNum());
    }

    @Override
    public Page<OrderComplaint> listComplaints(ComplaintStatus status, int page, int size) {
        if (status != null) {
            return complaintRepository.findByStatusOrderByCreateTimeAsc(status, PageRequest.of(page, size));
        }
        return complaintRepository.findAllByOrderByCreateTimeDesc(PageRequest.of(page, size));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveComplaint(Long complaintId, String adminNote) {
        OrderComplaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "投诉不存在"));
        if (complaint.getStatus() != ComplaintStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "该投诉已处理，当前状态: " + complaint.getStatus().getDesc());
        }

        try {
            weChatPayService.refund(complaint.getOrderNum(), complaint.getReason().getDesc());
        } catch (Exception e) {
            log.error("退款失败，订单号: {}, err: {}", complaint.getOrderNum(), e.getMessage());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.REFUND_FAILED,
                    "退款失败: " + e.getMessage());
        }

        complaint.setStatus(ComplaintStatus.APPROVED);
        complaint.setAdminNote(adminNote);
        complaint.setResolveTime(LocalDateTime.now());
        complaintRepository.save(complaint);

        log.info("投诉已批准并退款，投诉ID: {}, 订单号: {}", complaintId, complaint.getOrderNum());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectComplaint(Long complaintId, String adminNote) {
        OrderComplaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND,
                        "投诉不存在"));
        if (complaint.getStatus() != ComplaintStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                    "该投诉已处理，当前状态: " + complaint.getStatus().getDesc());
        }

        orderRepository.findByOrderNum(complaint.getOrderNum()).ifPresent(order -> {
            order.setOrderStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);
        });

        complaint.setStatus(ComplaintStatus.REJECTED);
        complaint.setAdminNote(adminNote);
        complaint.setResolveTime(LocalDateTime.now());
        complaintRepository.save(complaint);

        log.info("投诉已驳回，投诉ID: {}, 订单号: {}", complaintId, complaint.getOrderNum());
    }
}
