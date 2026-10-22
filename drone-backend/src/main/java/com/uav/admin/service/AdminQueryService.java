package com.uav.admin.service;

import com.uav.admin.pojo.vo.AdminOrderVo;
import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.admin.pojo.vo.AdminPilotDetailVo;
import com.uav.admin.pojo.vo.AdminPilotVo;
import com.uav.admin.pojo.vo.AdminTaskVo;
import com.uav.admin.pojo.vo.AdminUserDetailVo;
import com.uav.admin.pojo.vo.AdminUserVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade for admin read models: orders, tasks, users and pilots.
 *
 * <p>The implementation is split into focused query services; this class keeps
 * the controller-facing API stable.
 */
@Service
public class AdminQueryService {

    private final AdminOrderQueryService orderQueryService;
    private final AdminTaskQueryService taskQueryService;
    private final AdminSubjectQueryService subjectQueryService;

    public AdminQueryService(AdminOrderQueryService orderQueryService,
                             AdminTaskQueryService taskQueryService,
                             AdminSubjectQueryService subjectQueryService) {
        this.orderQueryService = orderQueryService;
        this.taskQueryService = taskQueryService;
        this.subjectQueryService = subjectQueryService;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminOrderVo> listOrders(int page, int size, String status, String orderNum, String taskNum) {
        return orderQueryService.listOrders(page, size, status, orderNum, taskNum);
    }

    @Transactional(readOnly = true)
    public AdminOrderVo getOrderDetail(String orderNum) {
        return orderQueryService.getOrderDetail(orderNum);
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminTaskVo> listTasks(int page, int size, String status, String taskNum) {
        return taskQueryService.listTasks(page, size, status, taskNum);
    }

    @Transactional(readOnly = true)
    public AdminTaskVo getTaskDetail(String taskNum) {
        return taskQueryService.getTaskDetail(taskNum);
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminUserVo> listUsers(int page, int size, String keyword, Integer status) {
        return subjectQueryService.listUsers(page, size, keyword, status);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailVo getUserDetail(Long userId) {
        return subjectQueryService.getUserDetail(userId);
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminPilotVo> listPilots(int page, int size, String keyword) {
        return subjectQueryService.listPilots(page, size, keyword);
    }

    @Transactional(readOnly = true)
    public AdminPilotDetailVo getPilotDetail(Long userId) {
        return subjectQueryService.getPilotDetail(userId);
    }
}
