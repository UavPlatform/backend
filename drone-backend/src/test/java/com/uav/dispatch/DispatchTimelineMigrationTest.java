package com.uav.dispatch;

import com.uav.support.TimelineMigrationFixture;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class DispatchTimelineMigrationTest {
    @Test void migrationUsesOnlyVerifiedEventsAndTheCurrentAssignment() throws Exception {
        TimelineMigrationFixture.verify("jdbc:h2:mem:timeline_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa");
    }
}
