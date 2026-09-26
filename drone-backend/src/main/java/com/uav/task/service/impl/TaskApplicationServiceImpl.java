package com.uav.task.service.impl;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.server.calculator.TransportPriceCalculator;
import com.uav.server.calculator.TransportPriceRejection;
import com.uav.server.calculator.TransportPriceResult;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.ApplicationStatus;
import com.uav.server.exception.BusinessException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

    @Transactional(rollbackFor = Exception.class)
    @Override
    public TaskApplicationVO apply(String taskNum, Long riderId, Long aircraftModelId) {
        if (taskNum == null || taskNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "taskNum 不能为空");
        }
        // 锁任务行：同一任务的应征串行，保证「查重 + 更新/插入」不并发撞唯一约束
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

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

        log.info("飞手应征: taskNum={}, riderId={}, model={}, quotedAmount={}元",
                taskNum, riderId, model.getModelCode(), quotedAmount);
        return buildVo(saved, task.getTaskNum(), model);
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
            vos.add(buildVo(application, task.getTaskNum(), model));
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

    private TaskApplicationVO buildVo(TaskApplication application, String taskNum, AircraftModel model) {
        TaskApplicationVO vo = new TaskApplicationVO();
        vo.setApplicationId(application.getId());
        vo.setTaskNum(taskNum);
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
