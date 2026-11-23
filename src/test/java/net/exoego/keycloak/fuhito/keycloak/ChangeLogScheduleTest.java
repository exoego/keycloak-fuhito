package net.exoego.keycloak.fuhito.keycloak;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeLogScheduleTest {

    @Test
    void theFirstRunComesAfterOneIntervalOrFifteenMinutesWhicheverIsShorter() {
        long fifteenMinutes = Duration.ofMinutes(15).toMillis();
        long day = Duration.ofDays(1).toMillis();

        assertEquals(List.of(5_000L, fifteenMinutes, fifteenMinutes),
                List.of(ChangeLogSchedule.firstDelayMillis(5_000), ChangeLogSchedule.firstDelayMillis(fifteenMinutes),
                        ChangeLogSchedule.firstDelayMillis(day)));
    }
}
