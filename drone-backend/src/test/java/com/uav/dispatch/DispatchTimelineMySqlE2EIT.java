package com.uav.dispatch;

import com.uav.support.RealProtocolTestBase;
import com.uav.support.TimelineMigrationFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0013: explicitly provisioned disposable databases, never the developer database. */
@EnabledIfEnvironmentVariable(named = "TIMELINE_MYSQL_URL", matches = "jdbc:mysql:.*")
class DispatchTimelineMySqlE2EIT extends RealProtocolTestBase {
    private String isolatedUrl(String variable) {
        String url = System.getenv(variable);
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:13316/timeline_");
        return url;
    }

    @Test void historicalBackfillUsesTheSameVerifiedEventsOnMySql() throws Exception {
        TimelineMigrationFixture.verify(isolatedUrl("TIMELINE_MYSQL_URL"), "root");
    }

    @Test void allMigrationsRunOnAnEmptyMySqlDatabase() {
        var flyway = Flyway.configure().dataSource(isolatedUrl("TIMELINE_MYSQL_FULL_URL"), "root", "")
                .locations("classpath:db/migration").load();
        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("24");
        assertThat(flyway.info().pending()).isEmpty();
    }
}
