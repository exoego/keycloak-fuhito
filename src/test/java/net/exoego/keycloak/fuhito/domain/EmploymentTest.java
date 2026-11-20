package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmploymentTest {

    private static Employment of(String employerId) {
        return new Employment("e1", "p1", null, employerId, ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), null,
                null, null, null, 1);
    }

    @Test
    void aPersonIsSecondedInAUnitOfAnotherLegalEntity() {
        assertTrue(of("company-a").secondedTo("company-b"));
        assertFalse(of("company-a").secondedTo("company-a"));
        // A unit that belongs to no legal entity, or an employment with no employer, gives no secondment.
        assertFalse(of("company-a").secondedTo(null));
        assertFalse(of(null).secondedTo("company-a"));
        assertFalse(of(null).secondedTo(null));
    }
}
