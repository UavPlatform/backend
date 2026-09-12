package com.uav.server.notify;

import com.uav.chat.notify.SystemNotificationService;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.util.UserContext;
import com.uav.task.pojo.entity.Task;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务/订单状态迁移捕获器（1B-3，裁决 Q9=A：站内系统消息 + WS 推送，离线 sync 补偿）。
 *
 * <p>被动的 Hibernate 拦截器：在刷洗时对比实体前后状态，捕获以下六类状态变更点
 * （BE P2-30 预留的 MsgType.NOTICE/ORDER 枚举在此补全为真实协议）：
 * <ol>
 *   <li>MissionOrder PENDING→PAID —— 支付成功（通知订单所有者）</li>
 *   <li>Task IDLE→IN_PROGRESS —— 接单（通知任务发布者）</li>
 *   <li>Task IN_PROGRESS→IDLE —— 取消接单（通知任务发布者）</li>
 *   <li>Task IN_PROGRESS→COMPLETED —— 完成（通知任务发布者）</li>
 *   <li>MissionOrder →WAITING_CONFIRM —— 待验收（通知任务发布者）</li>
 *   <li>MissionOrder WAITING_CONFIRM→COMPLETED —— 确认完成（通知接单飞手）</li>
 * </ol>
 *
 * <p>派发时机：捕获到首个迁移时向当前 Spring 事务注册 {@link TransactionSynchronization}，
 * 在 afterCommit（事务成功提交后、同线程）统一派发；回滚的事务只做清理，不产生任何通知。
 * 注意：Hibernate 7 的 Interceptor.afterTransactionCompletion 回调时事务状态已被重置为
 * NOT_ACTIVE，无法据此区分提交/回滚，因此不能在 Hibernate 回调里派发。
 *
 * <p>本拦截器在 entityManagerFactory 创建期间被 HibernatePropertiesCustomizer 消费，
 * 因此对派发服务只持有 {@link ObjectProvider}（惰性解析），避免创建期环形依赖。
 */
@Slf4j
@Component
public class SystemNotifyInterceptor implements Interceptor {

    /**
     * 事务资源标记的固定 key：必须与拦截器实例解耦——匿名同步器内部用 `this`
     * 作 key 会指向同步器对象本身而非拦截器，导致解绑失败、标记永久残留。
     */
    private static final Object TX_RESOURCE_KEY = new Object();

    private final ObjectProvider<SystemNotificationService> notificationServiceProvider;

    /**
     * 同一线程的事务刷洗与提交在同一线程执行，ThreadLocal 隔离并发请求。
     */
    private final ThreadLocal<List<NotificationDraft>> drafts = ThreadLocal.withInitial(ArrayList::new);

    public SystemNotifyInterceptor(ObjectProvider<SystemNotificationService> notificationServiceProvider) {
        this.notificationServiceProvider = notificationServiceProvider;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean onFlushDirty(Object entity, Object id, Object[] currentState,
                                Object[] previousState, String[] propertyNames, Type[] types) {
        try {
            if (entity instanceof MissionOrder order) {
                OrderStatus before = (OrderStatus) stateAt(previousState, propertyNames, "orderStatus");
                OrderStatus after = (OrderStatus) stateAt(currentState, propertyNames, "orderStatus");
                if (before == after || after == null) {
                    return false;
                }
                if (before == OrderStatus.PENDING && after == OrderStatus.PAID) {
                    add(NotificationDraft.of(order.getUserId(), MsgType.ORDER, "ORDER_PAID",
                            orderData(order), "任务支付成功，已进入接单大厅"));
                } else if (after == OrderStatus.WAITING_CONFIRM) {
                    add(NotificationDraft.of(order.getUserId(), MsgType.ORDER, "ORDER_WAITING_CONFIRM",
                            orderData(order), "飞手已完成任务，请前往验收确认"));
                } else if (before == OrderStatus.WAITING_CONFIRM && after == OrderStatus.COMPLETED) {
                    // 确认完成的对方是接单飞手，按 taskId 在派发阶段解析
                    Long taskId = order.getTask() != null ? order.getTask().getId() : null;
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("orderNum", String.valueOf(order.getOrderNum()));
                    data.put("taskId", taskId);
                    add(NotificationDraft.forTask(order.getUserId(), MsgType.ORDER, "ORDER_CONFIRMED",
                            data, "任务已确认完成", taskId));
                }
            } else if (entity instanceof Task task) {
                TaskStatus before = (TaskStatus) stateAt(previousState, propertyNames, "taskStatus");
                TaskStatus after = (TaskStatus) stateAt(currentState, propertyNames, "taskStatus");
                if (before == after || after == null) {
                    return false;
                }
                Long actorId = UserContext.getUserId();
                if (before == TaskStatus.IDLE && after == TaskStatus.IN_PROGRESS) {
                    Map<String, Object> data = baseData(task);
                    data.put("riderId", actorId);
                    add(NotificationDraft.of(task.getUserId(), MsgType.NOTICE, "TASK_ACCEPTED", data,
                            "您的任务已被飞手接单"));
                } else if (before == TaskStatus.IN_PROGRESS && after == TaskStatus.IDLE) {
                    add(NotificationDraft.of(task.getUserId(), MsgType.NOTICE, "TASK_CANCELLED", baseData(task),
                            "飞手已取消接单，任务重新进入接单大厅"));
                } else if (before == TaskStatus.IN_PROGRESS && after == TaskStatus.COMPLETED) {
                    add(NotificationDraft.of(task.getUserId(), MsgType.NOTICE, "TASK_COMPLETED", baseData(task),
                            "飞手已完成任务，请前往验收确认"));
                }
            }
            registerCommitDispatchIfNeeded();
        } catch (Exception e) {
            // 通知捕获失败不得影响业务事务
            log.error("状态通知捕获失败: {}", e.getMessage(), e);
        }
        return false;
    }

    private void add(NotificationDraft draft) {
        drafts.get().add(draft);
    }

    /**
     * 当前事务已收集到通知且尚未注册派发回调时，注册 afterCommit 派发（幂等）。
     */
    private void registerCommitDispatchIfNeeded() {
        if (drafts.get().isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY) != null) {
            return;
        }
        TransactionSynchronizationManager.bindResource(TX_RESOURCE_KEY, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                List<NotificationDraft> batch = drafts.get();
                drafts.remove();
                if (batch.isEmpty()) {
                    return;
                }
                try {
                    SystemNotificationService service = notificationServiceProvider.getIfAvailable();
                    if (service == null) {
                        log.warn("SystemNotificationService 尚未就绪，丢弃 {} 条状态通知", batch.size());
                        return;
                    }
                    service.dispatch(batch);
                } catch (Exception e) {
                    log.error("系统通知派发失败: {}", e.getMessage(), e);
                }
            }

            @Override
            public void afterCompletion(int status) {
                // 无论提交/回滚：清空草稿并解绑事务资源标记（用固定 KEY，与绑定一致），
                // 否则标记残留会让后续事务跳过注册
                drafts.remove();
                if (TransactionSynchronizationManager.hasResource(TX_RESOURCE_KEY)) {
                    TransactionSynchronizationManager.unbindResource(TX_RESOURCE_KEY);
                }
            }
        });
    }

    private Object stateAt(Object[] state, String[] propertyNames, String property) {
        for (int i = 0; i < propertyNames.length; i++) {
            if (property.equals(propertyNames[i])) {
                return state[i];
            }
        }
        return null;
    }

    private Map<String, Object> baseData(Task task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskNum", task.getTaskNum());
        data.put("taskName", task.getTaskName());
        return data;
    }

    private Map<String, Object> orderData(MissionOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNum", order.getOrderNum());
        data.put("taskId", order.getTask() != null ? order.getTask().getId() : null);
        return data;
    }
}
