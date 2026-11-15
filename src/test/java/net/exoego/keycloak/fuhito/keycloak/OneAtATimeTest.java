package net.exoego.keycloak.fuhito.keycloak;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OneAtATimeTest {

    private record Report(int users, boolean dryRun, List<String> writes, String note) {
    }

    @Test
    void theSummaryOfAReportHasTheNumbersAndTheSizesOfLists() {
        assertEquals(Map.of("users", 3, "dryRun", true, "writes", 2),
                OneAtATime.summary(new Report(3, true, List.of("a", "b"), "left out")));
        assertEquals(Map.of(), OneAtATime.summary("not a report"));
    }
}
