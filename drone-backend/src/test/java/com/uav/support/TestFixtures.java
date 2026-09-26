package com.uav.support;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.enums.TaskType;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.dto.WaypointDto;
import com.uav.task.pojo.entity.Task;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import com.uav.user.service.RiderUavService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 共享测试基建：数据库造数工厂（R7：用户、飞手、任务构造集中在 com.uav.support，
 * 不再逐类复制私有 {@code newUser()} / {@code twoWaypointTask()}；O2：无 @Test 的工具类）。
 *
 * <p>本工厂做「直连仓储造数」，造出的实体字段按各测试类的既有私有方法机械收编，语义保持不变
 * （例如用户密码在绕过登录的场景下沿用占位值 {@code irrelevant}）。只有无人机绑定（{@code rider_uav}）是例外：
 * {@link #bindDrone(User)} / {@link #bindDrone(User, String)} 必须走生产绑定路径
 * {@link RiderUavService#bindDrone(Long, String, Long)}（非空校验 + 机型校验 + 全局唯一校验），不得直接
 * {@code riderUavRepository.save} 造出与生产不一致的绑定状态。需要「真实身份」时走
 * {@link TestAccounts} 的真实注册/登录接口；需要「真实业务链路」时由测试调用对应 Service。
 *
 * <p>唯一命名 / 唯一客户端身份的公开入口是 {@link UniqueNames}
 * （{@code unique(prefix)}、{@code userName()}、{@code userName(prefix)}、{@code djiId()}、
 * {@code clientIp()}）；本工厂内部所有名字与 DJI ID 均由它生成，因 H2 全 JVM 共享而不依赖
 * {@code @Transactional} 回滚。
 */
@Component
public class TestFixtures {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RiderUavRepository riderUavRepository;

    @Autowired
    private RiderUavService riderUavService;

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private OrderRepository orderRepository;

    // ---------- 用户 / 飞手 ----------

    /** 造一个指定角色的用户（0 普通用户 / 1 飞手 / 2 管理员），用户名唯一。 */
    public User user(int role) {
        return user(UniqueNames.userName(), role);
    }

    /** 造一个指定用户名与角色的用户；密码为占位值（不用于登录）。 */
    public User user(String userName, int role) {
        User user = new User();
        user.setUserName(userName);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    /**
     * 取回一个已存在的用户实体并校验其角色，供「先用 {@link TestAccounts} 真实注册、之后需要
     * {@code User} 实体」的场景使用（例如 {@code fixtures.user(rider.id(), rider.role())}）。
     * 不做插入/更新；用户不存在或角色不符立即失败。
     */
    public User user(long userId, int role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("用户不存在: userId=" + userId));
        if (user.getRole() == null || user.getRole() != role) {
            throw new IllegalStateException("用户角色不符: userId=" + userId
                    + ", 期望=" + role + ", 实际=" + user.getRole());
        }
        return user;
    }

    /** 造一个飞手用户（role=1，未绑定无人机）。 */
    public User rider() {
        return user(1);
    }

    /**
     * 造一个已绑定唯一无人机的飞手（role=1），可通过 {@code @RequireDrone} 门槛，也可用其
     * {@code userId + djiId} 通过 {@code /ws/drone?deviceId=<djiId>&token=<token>} 握手鉴权。
     */
    public User riderWithDrone() {
        User rider = rider();
        bindDrone(rider);
        return rider;
    }

    /**
     * 给飞手绑定一台唯一无人机，使其能通过 {@code @RequireDrone} 门槛
     * （{@code JwtInterceptor} 以 {@code riderUavRepository.existsByUserId} 判定）。
     */
    public RiderUav bindDrone(User rider) {
        return bindDrone(rider, UniqueNames.djiId());
    }

    /**
     * 用指定 DJI ID 绑定（机型映射为默认种子 {@link #defaultAircraftModelId()}），
     * 走生产绑定路径 {@link RiderUavService#bindDrone(Long, String, Long)}
     * （非空校验 + 机型校验 + 全局唯一校验，与 {@code /rider/drone/bind} 同一条代码路径），
     * 而不是直接 {@code riderUavRepository.save} 造出与生产不一致的绑定状态。
     * DJI ID 全库唯一，重复会触发生产侧「该无人机已被绑定」。
     */
    public RiderUav bindDrone(User rider, String djiId) {
        return bindDrone(rider.getId(), djiId);
    }

    /**
     * 用 userId 绑定指定 DJI ID（核心入口，机型为默认种子 FC30）：走生产绑定路径
     * {@link RiderUavService#bindDrone(Long, String, Long)}，而不是直接
     * {@code riderUavRepository.save} 造出与生产不一致的绑定状态。
     *
     * <p>供真实协议测试把设备绑定到 {@link TestAccounts.Account#id()} 指定的真实飞手上
     * （例如 LiveRiderStartE2EIT 需要 {@code deviceId} 绑定到握手令牌的 userId）。
     */
    public RiderUav bindDrone(long userId, String djiId) {
        return bindDrone(userId, djiId, defaultAircraftModelId());
    }

    /**
     * 用 userId 绑定指定 DJI ID 并映射指定机型（{@code aircraftModelId} 为 {@code null}
     * 时造出与注册旧路径一致的未映射设备）：仍走生产绑定路径
     * {@link RiderUavService#bindDrone(Long, String, Long)}。
     */
    public RiderUav bindDrone(long userId, String djiId, Long aircraftModelId) {
        riderUavService.bindDrone(userId, djiId, aircraftModelId);
        return riderUavRepository.findByUserId(userId).stream()
                .filter(binding -> djiId.equals(binding.getDjiId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "绑定后未查到 rider_uav 记录: userId=" + userId + ", djiId=" + djiId));
    }

    /** 默认可吊运机型 ID（V2 迁移种子 {@code FC30}）；种子缺失时快速失败。 */
    public long defaultAircraftModelId() {
        return aircraftModelRepository.findByModelCode("FC30")
                .orElseThrow(() -> new IllegalStateException(
                        "缺少机型种子 FC30（V2__aircraft_model.sql 未执行？"))
                .getId();
    }

    /** 读取飞手当前绑定的第一个 DJI ID；未绑定时快速失败。 */
    public String djiIdOf(User rider) {
        return djiIdOf(rider.getId());
    }

    /** 读取指定 userId 当前绑定的第一个 DJI ID；未绑定时快速失败。 */
    public String djiIdOf(long userId) {
        return riderUavRepository.findByUserId(userId).stream()
                .map(RiderUav::getDjiId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("用户未绑定无人机: userId=" + userId));
    }

    // ---------- 任务 ----------

    /** 两个航点的 SURVEY 任务 DTO（收编各测试类重复的 twoWaypointTask）。 */
    public TaskDto twoWaypointTask() {
        return twoWaypointTask(UniqueNames.unique("task"));
    }

    /** 两个航点的 SURVEY 任务 DTO，任务名固定，便于按名断言。 */
    public TaskDto twoWaypointTask(String taskName) {
        TaskDto dto = new TaskDto();
        dto.setTaskName(taskName);
        dto.setType(TaskType.SURVEY);
        dto.setWaypoints(List.of(
                waypoint(0, 121.00, 31.00, 100.0),
                waypoint(1, 121.01, 31.00, 100.0)));
        return dto;
    }

    /** 单个航点的 SURVEY 任务 DTO（用于「航点不足导致计价为 0」等边界场景）。 */
    public TaskDto oneWaypointTask(String taskName) {
        TaskDto dto = new TaskDto();
        dto.setTaskName(taskName);
        dto.setType(TaskType.SURVEY);
        dto.setWaypoints(List.of(waypoint(0, 121.00, 31.00, 100.0)));
        return dto;
    }

    /** 直接落库一个任务实体（绕开 createTask 的下单/计价副作用，用于状态机与删除守卫场景）。 */
    public Task task(User owner, TaskStatus status, double reward) {
        return task(status, reward, owner.getId());
    }

    /**
     * 直接落库一个任务实体，归属用 userId 指定——可直接与 {@link TestAccounts.Account#id()}
     * 组合，推荐用于新写的测试。
     */
    public Task task(TaskStatus status, double reward, long ownerId) {
        Task task = new Task();
        task.setTaskNum(UniqueNames.unique("TN"));
        task.setTaskName(UniqueNames.unique("task"));
        task.setUserId(ownerId);
        task.setTaskType(TaskType.SURVEY);
        task.setTaskStatus(status);
        task.setReward(reward);
        return taskRepository.save(task);
    }

    /** 直接落库一个订单实体（金额/距离显式给定，避免依赖计价链路）。 */
    public MissionOrder order(User owner, Task task, OrderStatus status, String totalAmount) {
        return order(owner.getId(), task, status, totalAmount);
    }

    /**
     * 直接落库一个订单实体，归属用 userId 指定——可直接与 {@link TestAccounts.Account#id()}
     * 组合，推荐用于新写的测试。
     */
    public MissionOrder order(long ownerId, Task task, OrderStatus status, String totalAmount) {
        MissionOrder order = new MissionOrder();
        order.setOrderNum(UniqueNames.unique("ON"));
        order.setUserId(ownerId);
        order.setTask(task);
        order.setTotalAmount(new BigDecimal(totalAmount));
        order.setTotalDistance(new BigDecimal("198.00"));
        order.setOrderStatus(status);
        return orderRepository.save(order);
    }

    /** 把任务对应订单置为已支付；订单不存在时快速失败（收编各测试类的 markOrderPaid/markPaid）。 */
    public MissionOrder markOrderPaid(Task task) {
        MissionOrder order = orderRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new IllegalStateException("任务无对应订单: " + task.getId()));
        order.setOrderStatus(OrderStatus.PAID);
        return orderRepository.save(order);
    }

    private WaypointDto waypoint(int index, double longitude, double latitude, double altitude) {
        WaypointDto waypoint = new WaypointDto();
        waypoint.setOrderIndex(index);
        waypoint.setLongitude(longitude);
        waypoint.setLatitude(latitude);
        waypoint.setAltitude(altitude);
        return waypoint;
    }
}
