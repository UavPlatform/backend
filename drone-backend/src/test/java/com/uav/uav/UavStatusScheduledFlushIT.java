package com.uav.uav;

import com.uav.support.IntegrationTestBase;
import com.uav.support.UniqueNames;
import com.uav.uav.mapper.GpsRecordRepository;
import com.uav.uav.pojo.dto.UavStatusDto;
import com.uav.uav.pojo.entity.UavGpsRecord;
import com.uav.uav.service.impl.UavStatusServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8（R11）定时逻辑覆盖：{@code UavStatusServiceImpl#flushGpsRecords()}
 * ——{@code @Scheduled(fixedDelay = 5000)} 的「GPS 缓冲队列 → 批量落库」业务语义。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 Spring 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @ActiveProfiles("test")} + {@code @Transactional}），
 * 不占真实端口。{@code flushGpsRecords()} 未暴露在 {@code UavStatusService} 接口上，
 * 故按 R11「直调其服务方法」注入实现类直调；调度触发时机本身不作要求（§3）。
 *
 * <p>包归属（O1/R9）：被测类位于 {@code com.uav.uav.service.impl}，规范 §6.4 目标结构未列出该域，
 * 故按 O1「测试归业务域包」新建 {@code com.uav.uav} 测试包（与 {@code com.uav.order}、
 * {@code com.uav.pay} 等同级并列），而不放入 {@code com.uav.support}——后者只收无 {@code @Test}
 * 的共享基建（O2）。
 *
 * <p>隔离与断言（R5/R7）：GPS 缓冲队列是单例服务的内存字段，且被 {@code fixedDelay = 5000}
 * 的调度线程共享，因此本类不统计全表条数，只用 {@link UniqueNames} 生成的唯一
 * {@code deviceId} 与唯一 {@code uavId} 造数，并按自己的 {@code uavId} 反查自己的行。
 * 落库写入本测试的事务内，随回滚消失；内存中的状态表与设备-订单绑定不随事务回滚，
 * 故每个用例都用新的唯一设备号，并在用例内显式解除绑定。
 *
 * <p>如实披露的残留（不弱化断言、不以 sleep 规避）：若 5 秒调度恰好落在「入队」与「直调」之间，
 * 该记录会由调度线程落库（独立事务、立即提交）而不是本次直调；此时断言仍成立
 * ——{@code LinkedBlockingQueue#drainTo} 保证同一记录只被排空一次，因此"只落一条"始终为真，
 * 但"是直调写的还是调度写的"无法从外部区分。
 */
class UavStatusScheduledFlushIT extends IntegrationTestBase {

    /** 测试用 uavId 起点：远高于业务自增 id，避免与真实/其它用例数据混淆。 */
    private static final AtomicLong UAV_ID_SEQ = new AtomicLong(9_000_000_000L);

    private static final long SAMPLE_TIMESTAMP = 1_700_000_000_000L;

    /** 直调定时方法（未暴露在接口上，R11：直调其服务方法）。 */
    @Autowired
    private UavStatusServiceImpl uavStatusService;

    @Autowired
    private GpsRecordRepository gpsRecordRepository;

    @Test
    @DisplayName("flushGpsRecords：缓冲中的 GPS 点批量落库，且同一条只落一次")
    void flushGpsRecordsPersistsBufferedPointOnce() {
        long uavId = UAV_ID_SEQ.incrementAndGet();
        String deviceId = UniqueNames.djiId();

        uavStatusService.updateUavStatus(deviceId, sampleStatus(uavId, deviceId));
        uavStatusService.flushGpsRecords();
        // 再冲一次：缓冲已被排空 → 不得重复落库
        uavStatusService.flushGpsRecords();

        List<UavGpsRecord> mine = recordsOf(uavId);
        assertThat(mine).hasSize(1);

        UavGpsRecord record = mine.get(0);
        assertThat(record.getUavId()).isEqualTo(uavId);
        assertThat(record.getDeviceId()).isEqualTo(deviceId);
        assertThat(record.getLongitude()).isEqualTo(121.4737);
        assertThat(record.getLatitude()).isEqualTo(31.2304);
        assertThat(record.getAltitude()).isEqualTo(88.0);
        assertThat(record.getSpeed()).isEqualTo(7.5);
        assertThat(record.getBattery()).isEqualTo(76);
        assertThat(record.getFlightStatus()).isEqualTo(2);
        assertThat(record.getOperation()).isEqualTo("巡检");
        assertThat(record.getTimestamp()).isEqualTo(SAMPLE_TIMESTAMP);
        assertThat(record.getReceivedAt()).isEqualTo(SAMPLE_TIMESTAMP);
        // 该设备未绑定订单 → 落库记录不带订单号
        assertThat(record.getOrderNum()).isNull();
    }

    @Test
    @DisplayName("flushGpsRecords：已绑定订单的设备，落库记录带上订单号；解绑后回落为 null")
    void flushGpsRecordsCarriesBoundOrderNum() {
        long uavId = UAV_ID_SEQ.incrementAndGet();
        String deviceId = UniqueNames.djiId();
        String orderNum = UniqueNames.unique("P8ORD");

        uavStatusService.bindDeviceToOrder(deviceId, orderNum);
        try {
            uavStatusService.updateUavStatus(deviceId, sampleStatus(uavId, deviceId));
            uavStatusService.flushGpsRecords();

            List<UavGpsRecord> mine = recordsOf(uavId);
            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).getOrderNum()).isEqualTo(orderNum);
        } finally {
            // 设备-订单绑定是内存态（不随事务回滚），用例内显式解除，避免残留影响其它用例
            uavStatusService.clearDeviceOrderBinding(deviceId);
        }
        assertThat(uavStatusService.getOrderNumByDevice(deviceId)).isNull();
    }

    @Test
    @DisplayName("flushGpsRecords：批量上限边界——201 条候选分两批全部落库（BATCH_SIZE = 200）")
    void flushGpsRecordsDrainsBatchesWithoutLosingRecords() {
        long uavId = UAV_ID_SEQ.incrementAndGet();
        String deviceId = UniqueNames.djiId();

        // BATCH_SIZE(200) + 1：单次排空至多 200 条，余下留待下次；两次之后不得丢记录、不得重复
        int pushed = 201;
        for (int i = 0; i < pushed; i++) {
            uavStatusService.updateUavStatus(deviceId, sampleStatus(uavId, deviceId));
        }
        uavStatusService.flushGpsRecords();
        uavStatusService.flushGpsRecords();

        assertThat(recordsOf(uavId)).hasSize(pushed);
    }

    private static UavStatusDto sampleStatus(long uavId, String deviceId) {
        UavStatusDto status = new UavStatusDto();
        status.setUavId(uavId);
        status.setDeviceId(deviceId);
        status.setUavName("P8 巡检机");
        status.setLongitude(121.4737);
        status.setLatitude(31.2304);
        status.setAltitude(88.0);
        status.setSpeed(7.5);
        status.setBattery(76);
        status.setFlightStatus(2);
        status.setOperation("巡检");
        status.setTimestamp(SAMPLE_TIMESTAMP);
        status.setReceivedAt(SAMPLE_TIMESTAMP);
        return status;
    }

    /** 只取本用例自己 {@code uavId} 的行（唯一 id 隔离，不统计全表）。 */
    private List<UavGpsRecord> recordsOf(long uavId) {
        return gpsRecordRepository.findByUavIdAndTimestampAfterOrderByTimestampAsc(uavId, 0L);
    }
}
