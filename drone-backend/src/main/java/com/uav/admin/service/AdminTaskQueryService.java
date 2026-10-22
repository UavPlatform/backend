package com.uav.admin.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.admin.pojo.vo.AdminTaskVo;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Admin task list and detail queries. */
@Service
public class AdminTaskQueryService {

    private final AdminQuerySupport support;

    public AdminTaskQueryService(AdminQuerySupport support) {
        this.support = support;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminTaskVo> listTasks(int page, int size, String status, String taskNum) {
        int[] ps = support.sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder();
        TaskStatus taskStatus = support.resolveTaskStatus(status);
        if (taskStatus != null) {
            where.append(" AND t.taskStatus = :taskStatus");
            params.put("taskStatus", taskStatus);
        }
        if (support.notBlank(taskNum)) {
            where.append(" AND t.taskNum = :taskNum");
            params.put("taskNum", taskNum.trim());
        }
        String whereSql = where.isEmpty() ? "" : "WHERE " + where.substring(5);

        List<com.uav.task.pojo.entity.Task> tasks = support.bindParams(
                support.em().createQuery("SELECT t FROM Task t " + whereSql + " ORDER BY t.createTime DESC",
                        com.uav.task.pojo.entity.Task.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = support.bindParams(
                support.em().createQuery("SELECT COUNT(t) FROM Task t " + whereSql, Long.class), params)
                .getSingleResult();

        List<AdminTaskVo> content = tasks.stream().map(support::toTaskVo).collect(Collectors.toList());
        return AdminPageVo.of(content, ps[0], ps[1], total);
    }

    @Transactional(readOnly = true)
    public AdminTaskVo getTaskDetail(String taskNum) {
        com.uav.task.pojo.entity.Task task = support.em()
                .createQuery("SELECT t FROM Task t WHERE t.taskNum = :taskNum",
                        com.uav.task.pojo.entity.Task.class)
                .setParameter("taskNum", taskNum)
                .getResultStream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        return support.toTaskVo(task);
    }
}
