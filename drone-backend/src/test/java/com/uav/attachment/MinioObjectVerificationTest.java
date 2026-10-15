package com.uav.attachment;

import com.uav.attachment.service.MinioStorageService;
import com.uav.server.exception.BusinessException;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinioObjectVerificationTest {
    MinioStorageService storage = new MinioStorageService("http://localhost:9000", "test", "test-secret", "test", "us-east-1");
    MinioClient client = mock(MinioClient.class);

    @BeforeEach void setup() { ReflectionTestUtils.setField(storage, "minioClient", client); }

    @Test void exactPositiveSizeIsRequired() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        assertThat(storage.isValidObject("key", 1024L)).isTrue();
        assertThat(storage.isValidObject("key", 1023L)).isFalse();
        assertThat(storage.isValidObject("key", 0L)).isFalse();
    }

    @Test void missingKeyIsInvalidButMissingBucketIsInfrastructureFailure() throws Exception {
        ErrorResponseException error = mock(ErrorResponseException.class);
        ErrorResponse response = mock(ErrorResponse.class);
        when(error.errorResponse()).thenReturn(response);
        when(response.code()).thenReturn("NoSuchKey", "NoSuchBucket");
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(error);
        assertThat(storage.isValidObject("key", 1024L)).isFalse();
        assertThatThrownBy(() -> storage.isValidObject("key", 1024L)).isInstanceOf(BusinessException.class);
    }

    @Test void migrationPreservesLegacyRowsAndDefaultsNewRowsToPending() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:attachment_migration;MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE task_attachment (id INT PRIMARY KEY)");
            statement.execute("INSERT INTO task_attachment(id) VALUES (1)");
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V9__attachment_upload_status.sql"));
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            statement.execute("INSERT INTO task_attachment(id) VALUES (2)");
            try (var rows = statement.executeQuery("SELECT upload_status FROM task_attachment ORDER BY id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("LEGACY");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("PENDING");
            }
        }
    }
}
