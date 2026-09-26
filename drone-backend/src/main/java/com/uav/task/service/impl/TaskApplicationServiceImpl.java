package com.uav.task.service.impl;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.chat.notify.SystemNotificationService;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.server.calculator.TransportPriceCalculator;
import com.uav.server.calculator.TransportPriceRejection;
import com.uav.server.calculator.TransportPriceResult;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.ApplicationStatus;
import com.uav.server.enums.MatchStatus;
import com.uav.server.exception.BusinessException;
import com.uav.server.notify.NotificationDraft;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.task.pojo.vo.TaskApplicationVO;
import com.uav.task.service.TaskApplicationService;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import com.uav.user.service.RiderUavService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞手应征实现（TASK-BACKEND-003 / REQ-BACKEND-001 / ADR-0003）。
 *
 * <p>链路：任务存在 → {@code requireTransportDevice} 设备/机型门禁 →
 * {@link TransportPriceCalculator} 按「任务航点距离 + 货物重量/类别 + 机型系数」计价 →
 * 拒绝分支映射明确错误码（不落库），成功分支持久化 {@code quotedAmount}。
 *
 * <p><b>ADR-0003「不允许改价」</b>：本服务不暴露任何金额入参；金额唯一来源是计价器输出。
 * 同一飞手同一任务重复应征更新原记录并重新计价（唯一约束
 * {@code uk_task_application_task_rider} + 任务行悲观锁保证并发下查重安全）。
 */
@Slf4j
@Service
public class TaskApplicationServiceImpl implements TaskApplicationService {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskApplicationRepository applicationRepository;

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RiderUavService riderUavService;

    @Autowired
    private TransportPriceCalculator priceCalculator;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ObjectProvider<SystemNotificationService> notificationServiceProvider;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public TaskApplicationVO apply(String taskNum, Long riderId, Long aircraftModelId) {
        if (taskNum == null || taskNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "taskNum 不能为空");
        }
        // 锁任务行：同一任务的应征串行，保证「查重 + 更新/插入」不并发撞唯一约束
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        // 不能应征自己的任务（承接旧 acceptTask 的同名守卫）
        if (task.getUserId().equals(riderId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "不能应征自己的任务");
        }
        // 撮合状态门禁：仅招募中/洽谈中可应征（选定/支付/确认/验收/结案阶段一律拒绝）
        if (task.getMatchStatus() != MatchStatus.SEEKING_RIDER
                && task.getMatchStatus() != MatchStatus.NEGOTIATING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.MATCH_STATUS_INVALID,
                    "任务当前撮合状态不接受新应征: " + task.getMatchStatus());
        }

        // 门禁：绑定设备 + 设备映射到该机型 + 机型可吊运
        // （UAV_NOT_FOUND / AIRCRAFT_MODEL_REQUIRED / AIRCRAFT_MODEL_NOT_FOUND /
        //  AIRCRAFT_MODEL_NOT_TRANSPORTABLE / AIRCRAFT_MODEL_MISMATCH）
        AircraftModel model = riderUavService.requireTransportDevice(riderId, aircraftModelId);

        if (task.getCargoWeightKg() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "任务未填写货物重量，无法计算吊运报价");
        }

        TransportPriceResult result = priceCalculator.quote(
                task.getWaypoints(),
                task.getCargoWeightKg(),
                task.getCargoCategory() == null ? null : task.getCargoCategory().name(),
                model.getCoefficient(),
                model.getMaxPayloadKg());

        BigDecimal quotedAmount = switch (result) {
            case TransportPriceResult.Quote quote -> quote.quotedAmount();
            case TransportPriceResult.Rejected rejected -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST, rejectionCode(rejected.reason()), rejected.message());
        };

        TaskApplication application = applicationRepository
                .findByTaskIdAndRiderId(task.getId(), riderId)
                .orElseGet(() -> {
                    TaskApplication created = new TaskApplication();
                    created.setTaskId(task.getId());
                    created.setRiderId(riderId);
                    return created;
                });
        application.setAircraftModelId(model.getId());
        application.setQuotedAmount(quotedAmount);
        application.setStatus(ApplicationStatus.ACTIVE);
        TaskApplication saved = applicationRepository.save(application);

        // 撮合状态推进：首条应征时 SEEKING_RIDER → NEGOTIATING（其余阶段已在上方门禁拦截）
        MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.NEGOTIATING);
        if (task.getMatchStatus() == MatchStatus.SEEKING_RIDER) {
            task.setMatchStatus(MatchStatus.NEGOTIATING);
            taskRepository.save(task);
        }

        // 通知任务属主「收到应征」（事务提交后派发；含飞手/机型/系统报价）
        notifyOwnerAfterCommit(task, saved, model);

        log.info("飞手应征: taskNum={}, riderId={}, model={}, quotedAmount={}元",
                taskNum, riderId, model.getModelCode(), quotedAmount);
        return buildVo(task, saved, model);
    }

    /**
     * 应征通知（TASK-BACKEND-004 冲突点 4）：事务成功提交后向任务属主派发 APPLICATION_RECEIVED
     * 系统消息；回滚事务不派发（与 SystemNotifyInterceptor 的 afterCommit 语义一致）。
     */
    private void notifyOwnerAfterCommit(Task task, TaskApplication application, AircraftModel model) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskNum", task.getTaskNum());
        data.put("taskName", task.getTaskName());
        data.put("riderId", application.getRiderId());
        data.put("aircraftModelName", model.getDisplayName());
        data.put("quotedAmount", application.getQuotedAmount());
        NotificationDraft draft = NotificationDraft.of(task.getUserId(), MsgType.NOTICE,
                "APPLICATION_RECEIVED", data,
                "收到飞手应征报价 ¥" + application.getQuotedAmount().toPlainString()
                        + "（" + model.getDisplayName() + "），可查看并选定");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    SystemNotificationService service = notificationServiceProvider.getIfAvailable();
                    if (service != null) {
                        service.dispatch(List.of(draft));
                    }
                } catch (Exception e) {
                    log.error("应征通知派发失败: taskNum={}, {}", task.getTaskNum(), e.getMessage(), e);
                }
            }
        });
    }

    @Transactional(readOnly = true)
    @Override
    public List<TaskApplicationVO> listByTask(String taskNum, Long userId) {
        // 属主校验复用既有约定：非属主 → FORBIDDEN + ROUTE_NOT_FOUND（"无权查看此任务"）
        Task task = taskService.getTaskByTaskNum(taskNum, userId);
        List<TaskApplication> applications = applicationRepository
                .findByTaskIdOrderByCreateTimeAsc(task.getId());
        List<TaskApplicationVO> vos = new ArrayList<>(applications.size());
        for (TaskApplication application : applications) {
            AircraftModel model = aircraftModelRepository.findById(application.getAircraftModelId())
                    .orElseThrow(() -> new IllegalStateException(
                            "应征记录引用的机型不存在: " + application.getAircraftModelId()));
            vos.add(buildVo(task, application, model));
        }
        return vos;
    }

    /** 计价拒绝原因 → 明确错误码（密封类型穷举，新增拒绝原因必须在此显式映射）。 */
    private static ApiErrorCode rejectionCode(TransportPriceRejection reason) {
        return switch (reason) {
            case EXCEEDS_PAYLOAD -> ApiErrorCode.EXCEEDS_PAYLOAD;
            case INVALID_INPUT -> ApiErrorCode.INVALID_PARAM;
        };
    }

    private TaskApplicationVO buildVo(Task task, TaskApplication application, AircraftModel model) {
        TaskApplicationVO vo = new TaskApplicationVO();
        vo.setApplicationId(application.getId());
        vo.setTaskNum(task.getTaskNum());
        vo.setMatchStatus(task.getMatchStatus());
        vo.setRiderId(application.getRiderId());
        vo.setRiderName(userRepository.findById(application.getRiderId())
                .map(User::getUserName).orElse(null));
        vo.setAircraftModelId(model.getId());
        vo.setModelCode(model.getModelCode());
        vo.setAircraftModelName(model.getDisplayName());
        vo.setMaxPayloadKg(model.getMaxPayloadKg());
        vo.setQuotedAmount(application.getQuotedAmount());
        vo.setStatus(application.getStatus());
        vo.setAppliedAt(application.getCreateTime());
        return vo;
    }
}
