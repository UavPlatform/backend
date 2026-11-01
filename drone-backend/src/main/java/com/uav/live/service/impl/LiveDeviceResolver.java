package com.uav.live.service.impl;

import com.uav.equipment.mapper.DroneBorrowRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.equipment.pojo.entity.DroneBorrow;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.task.mapper.TaskAssignmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 任务→设备：派单活动连机优先，其次已批准借用机身号中在线的一台（无 rider_uav）。 */
@Slf4j
@Service
public class LiveDeviceResolver {

    public record TaskLiveDevice(String deviceId, String liveState) {
        public static TaskLiveDevice idle() { return new TaskLiveDevice(null, "IDLE"); }
    }

    private final TaskAssignmentRepository taskAssignmentRepository;
    private final FlightConnectionRepository connections;
    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;
    private final AppWebSocketService appWebSocketService;
    private final LiveSessionService liveSessionService;

    public LiveDeviceResolver(TaskAssignmentRepository taskAssignmentRepository,
                              FlightConnectionRepository connections,
                              ProviderDroneRepository drones,
                              DroneBorrowRepository borrows,
                              AppWebSocketService appWebSocketService,
                              LiveSessionService liveSessionService) {
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.connections = connections;
        this.drones = drones;
        this.borrows = borrows;
        this.appWebSocketService = appWebSocketService;
        this.liveSessionService = liveSessionService;
    }

    public TaskLiveDevice resolveForTask(Long taskId) {
        if (taskId == null) return TaskLiveDevice.idle();
        var assignment = taskAssignmentRepository.findByTaskId(taskId).orElse(null);
        if (assignment == null) return TaskLiveDevice.idle();
        return resolveForRider(assignment.getRiderId());
    }

    public TaskLiveDevice resolveForRider(Long riderId) {
        if (riderId == null) return TaskLiveDevice.idle();
        String connected = connections.findFirstByPilotIdAndActiveSerialNotNullOrderByOpenedAtDesc(riderId)
                .map(c -> c.getActiveSerial())
                .filter(s -> s != null && appWebSocketService.isConnected(s))
                .orElse(null);
        if (connected == null) {
            connected = borrows.findByPilotIdAndStatusOrderByBorrowedAtDesc(riderId, DroneBorrow.APPROVED).stream()
                    .map(DroneBorrow::getDroneId)
                    .filter(id -> id != null)
                    .map(id -> drones.findById(id).orElse(null))
                    .filter(d -> d != null)
                    .map(d -> d.getSerialNumber())
                    .filter(s -> s != null && appWebSocketService.isConnected(s))
                    .findFirst()
                    .orElse(null);
        }
        if (connected == null) return TaskLiveDevice.idle();
        String liveState = liveSessionService.getSnapshot(connected).getState().name();
        log.debug("飞手 {} 解析到在线设备 {}（liveState={}）", riderId, connected, liveState);
        return new TaskLiveDevice(connected, liveState);
    }
}
