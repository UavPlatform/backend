package com.uav.order.service;

import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.server.enums.ComplaintReason;
import com.uav.server.enums.ComplaintStatus;
import org.springframework.data.domain.Page;

public interface OrderComplaintService {

    void submitComplaint(String orderNum, Long userId, ComplaintReason reason, String description);

    OrderComplaint getComplaint(String orderNum, Long userId);

    void cancelComplaint(Long complaintId, Long userId);

    Page<OrderComplaint> listComplaints(ComplaintStatus status, int page, int size);

    void approveComplaint(Long complaintId, String adminNote);

    void rejectComplaint(Long complaintId, String adminNote);
}
