package com.uav.upload;

import com.uav.server.enums.FileUploadStatus;
import com.uav.support.IntegrationTestBase;
import com.uav.support.UniqueNames;
import com.uav.upload.config.UploadStorageConfig;
import com.uav.upload.entity.UploadedFile;
import com.uav.upload.repository.UploadRepository;
import com.uav.upload.service.impl.UploadRecordServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8（R11）定时逻辑覆盖：{@code UploadRecordServiceImpl#cleanupStaleUploads()}
 * ——{@code @Scheduled(fixedRate = 3600000)} 的「过期 PENDING_SIGN 上传会话 → FAILED」业务语义。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 Spring 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @ActiveProfiles("test")} + {@code @Transactional}），
 * 不占真实端口。方法未暴露在 {@code UploadRecordService} 接口上，故按 R11「直调其服务方法」
 * 注入实现类直调；调度触发时机本身不作要求（§3，{@code fixedRate = 3600000} 在用例执行期内不会触发）。
 *
 * <p>包归属（O1/R9）：被测类位于 {@code com.uav.upload.service.impl}，规范 §6.4 目标结构未列出该域，
 * 故按 O1「测试归业务域包」新建 {@code com.uav.upload} 测试包，与相邻的
 * {@code com.uav.attachment}（交付物上传域）并列，不混入 {@code com.uav.support}
 * ——后者只收无 {@code @Test} 的共享基建（O2）。
 *
 * <p>隔离与断言（R5/R7）：只用 {@link UniqueNames} 造自己的上传会话，断言只针对自己插入的行
 * （按 id 反查），不统计全表条数；状态推进发生在测试事务内，随回滚消失。
 *
 * <p>必要适配（如实披露，非断言弱化）：{@code UploadedFile} 的 {@code @PrePersist} 无条件把
 * {@code createTime} 覆盖为当前时间，无法直接 {@code save()} 造出「已过期」的行，故先
 * {@code saveAndFlush} 落库，再用同一事务内的 SQL 回填 {@code create_time}
 * （该列 {@code updatable = false}，回填不会被后续 flush 覆盖）。
 *
 * <p>存储未配置时的分支：默认 test profile 未注入 minio 配置，
 * {@code uploadStorageService.deleteFile(...)} 会失败并被定时方法自身的 try/catch 吞掉；
 * 因而本类同时也是"OSS 删除失败不影响状态推进"这一分支的覆盖。
 */
class UploadStaleCleanupIT extends IntegrationTestBase {

    /** 直调定时方法（未暴露在接口上，R11：直调其服务方法）。 */
    @Autowired
    private UploadRecordServiceImpl uploadRecordService;

    @Autowired
    private UploadRepository uploadRepository;

    @Autowired
    private UploadStorageConfig storageConfig;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("cleanupStaleUploads：超过 TTL 的 PENDING_SIGN → FAILED，未过期与已完成不受影响")
    void cleanupMarksOnlyExpiredPendingSessionsFailed() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expired = now.minusHours(storageConfig.getUploadTtlHours() + 1);

        UploadedFile expiredPending = insertSession(FileUploadStatus.PENDING_SIGN, expired);
        UploadedFile freshPending = insertSession(FileUploadStatus.PENDING_SIGN, now.minusMinutes(1));
        UploadedFile expiredCompleted = insertSession(FileUploadStatus.COMPLETED, expired);

        uploadRecordService.cleanupStaleUploads();

        // OSS 删除在未配置环境下失败，但状态推进照常发生
        assertThat(statusOf(expiredPending)).isEqualTo(FileUploadStatus.FAILED);
        // 未过期（严格晚于 deadline）的待上传会话保留
        assertThat(statusOf(freshPending)).isEqualTo(FileUploadStatus.PENDING_SIGN);
        // 状态过滤：只有 PENDING_SIGN 参与清理
        assertThat(statusOf(expiredCompleted)).isEqualTo(FileUploadStatus.COMPLETED);
    }

    @Test
    @DisplayName("cleanupStaleUploads：无过期会话时不改写任何记录（updateTime 不变）")
    void cleanupWithoutStaleSessionsWritesNothing() {
        UploadedFile fresh = insertSession(FileUploadStatus.PENDING_SIGN, LocalDateTime.now().minusMinutes(1));
        LocalDateTime updateTimeBefore = fresh.getUpdateTime();

        uploadRecordService.cleanupStaleUploads();

        UploadedFile after = reload(fresh.getId());
        assertThat(after.getUploadStatus()).isEqualTo(FileUploadStatus.PENDING_SIGN);
        assertThat(after.getUpdateTime()).isEqualTo(updateTimeBefore);
    }

    /** 造一条自己的上传会话；{@code createTime} 由 {@code @PrePersist} 强制为当前时间，故过期场景需回填。 */
    private UploadedFile insertSession(FileUploadStatus status, LocalDateTime createTime) {
        UploadedFile file = UploadedFile.builder()
                .uploadId(UniqueNames.unique("P8UP"))
                .originalName(UniqueNames.unique("p8") + ".jpg")
                .storagePath("/p8-stale/" + UniqueNames.unique("p8path") + ".jpg")
                .fileSize(1024L)
                .uploadStatus(status)
                .userId(fixtures.user(0).getId())
                .build();
        UploadedFile saved = uploadRepository.saveAndFlush(file);
        jdbcTemplate.update("update uploaded_file set create_time = ? where id = ?",
                Timestamp.valueOf(createTime), saved.getId());
        return saved;
    }

    private UploadedFile reload(Long id) {
        return uploadRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("上传会话不存在: id=" + id));
    }

    private FileUploadStatus statusOf(UploadedFile file) {
        return reload(file.getId()).getUploadStatus();
    }
}
