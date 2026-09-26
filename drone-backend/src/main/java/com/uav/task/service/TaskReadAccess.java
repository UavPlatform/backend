package com.uav.task.service;

import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.Task;
import org.springframework.stereotype.Component;

/**
 * 任务上下文<b>只读</b>放行判定（TASK-BACKEND-007 / REQ-FRONTEND-001 验收 4 / ADR-0004 监管=旁听只读）。
 *
 * <p>可读 = 任务属主 / 应征飞手（含已接单飞手）/ 管理员（role=2）；其余一律不可读。
 * 仅用于读端点（应征列表、履约证据列表与下载凭证）——写路径（上传、应征、选定）的权限口径不变，
 * 仍由各写端点自身的「属主/接单飞手」校验把守。
 *
 * <p>管理员分支在最前：监管平台会话（role=2）对任意任务的上下文只读可见，
 * 与 ADR-0004「Web=监管平台、监管=旁听只读」一致；role 为空（无登录上下文）不放行。
 */
@Component
public class TaskReadAccess {

    /** user.role：2 管理员（监管平台会话）。 */
    private static final int ROLE_ADMIN = 2;

    private final TaskApplicationRepository taskApplicationRepository;

    private final TaskAssignmentRepository taskAssignmentRepository;

    public TaskReadAccess(TaskApplicationRepository taskApplicationRepository,
                          TaskAssignmentRepository taskAssignmentRepository) {
        this.taskApplicationRepository = taskApplicationRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
    }

    /**
     * 当前调用者是否可读该任务的上下文（应征/证据等只读数据）。
     *
     * @param task     已加载的任务（不存在的任务由调用方先按 404 处理）
     * @param callerId 当前登录用户 ID
     * @param role     当前登录角色（0 普通用户 / 1 飞手 / 2 管理员），可为 null
     */
    public boolean canRead(Task task, Long callerId, Integer role) {
        if (task == null || callerId == null) {
            return false;
        }
        if (role != null && role == ROLE_ADMIN) {
            return true;
        }
        if (callerId.equals(task.getUserId())) {
            return true;
        }
        // 应征飞手：已提交应征即在任务上下文内；接单记录（task_assignment）单独兜底
        if (taskApplicationRepository.existsByTaskIdAndRiderId(task.getId(), callerId)) {
            return true;
        }
        return taskAssignmentRepository.findByTaskId(task.getId())
                .map(assignment -> callerId.equals(assignment.getRiderId()))
                .orElse(false);
    }
}
