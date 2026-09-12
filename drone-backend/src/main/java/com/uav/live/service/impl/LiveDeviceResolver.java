package com.uav.live.service.impl;

import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.pojo.entity.RiderUav;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务→设备映射解析器（1B-4b 微任务，裁决 Q3=C；t38 用户端直播观看依赖）。
 *
 * <p>解析链（冻结契约）：taskId → task_assignment.rider_id → rider_uav 绑定设备 →
 * 取其中「在线」的一台（appWebSocketService.isConnected）。
 * 无接单记录 / 无绑定设备 / 设备均离线 → deviceId=null 且 liveState=IDLE。
 *
 * <p>用途：TaskVo / OrderVO 携带 deviceId + liveState，用户端进详情页即可判定
 * 「观看直播」入口并调用 /live/get 拉取凭证。
 */
@Slf4j
@Service
public class LiveDeviceResolver {

    /** 解析结果：deviceId 为 null 表示当前无可观看的在线设备；liveState 为 LiveSession 状态名。 */
    public record TaskLiveDevice(String deviceId, String liveState) {
        public static TaskLiveDevice idle() {
            return new TaskLiveDevice(null, "IDLE");
        }
    }

    private final TaskAssignmentRepository taskAssignmentRepository;

    private final RiderUavRepository riderUavRepository;

    private final AppWebSocketService appWebSocketService;

    private final LiveSessionService liveSessionService;

    public LiveDeviceResolver(TaskAssignmentRepository taskAssignmentRepository,
                              RiderUavRepository riderUavRepository,
                              AppWebSocketService appWebSocketService,
                              LiveSessionService liveSessionService) {
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.riderUavRepository = riderUavRepository;
        this.appWebSocketService = appWebSocketService;
        this.liveSessionService = liveSessionService;
    }

    /**
     * 解析任务关联的作业设备与其直播态（只读，可安全在详情页路径调用）。
     */
    public TaskLiveDevice resolveForTask(Long taskId) {
        if (taskId == null) {
            return TaskLiveDevice.idle();
        }
        var assignment = taskAssignmentRepository.findByTaskId(taskId).orElse(null);
        if (assignment == null) {
            return TaskLiveDevice.idle(); // 尚未接单：无作业设备
        }
        List<RiderUav> bindings = riderUavRepository.findByUserId(assignment.getRiderId());
        String connectedDeviceId = bindings.stream()
                .map(RiderUav::getDjiId)
                .filter(appWebSocketService::isConnected)
                .findFirst()
                .orElse(null);
        if (connectedDeviceId == null) {
            return TaskLiveDevice.idle(); // 绑定设备均离线
        }
        String liveState = liveSessionService.getSnapshot(connectedDeviceId).getState().name();
        log.debug("任务 {} 解析到在线设备 {}（liveState={}）", taskId, connectedDeviceId, liveState);
        return new TaskLiveDevice(connectedDeviceId, liveState);
    }
}
