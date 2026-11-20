package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmploymentTest {

    private static Employment of(Employer employer) {
        return new Employment("e1", "p1", null, employer, ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), null,
                null, null, null, 1);
    }

    @Test
    void aPersonIsSecondedInAUnitOfAnotherLegalEntity() {
        Employment ofA = of(Employer.legalEntity("company-a"));
        assertTrue(ofA.secondedTo("company-b"));
        assertFalse(ofA.secondedTo("company-a"));
        // A unit that belongs to no legal entity gives no secondment.
        assertFalse(ofA.secondedTo(null));
    }

    @Test
    void anEmploymentWithAnExternalEmployerIsNeverASecondment() {
        Employment ofAgency = of(Employer.external("agency"));
        assertFalse(ofAgency.secondedTo("company-a"));
        assertFalse(ofAgency.secondedTo("agency"));
        assertFalse(ofAgency.secondedTo(null));
    }

    @Test
    void anEmployerIsALegalEntityOrAnExternalEmployer() {
        Employer company = Employer.legalEntity("company-a");
        Employer agency = Employer.external("agency");
        assertEquals("company-a", company.legalEntityId());
        assertNull(company.externalEmployerId());
        assertFalse(company.isExternal());
        assertNull(agency.legalEntityId());
        assertEquals("agency", agency.externalEmployerId());
        assertTrue(agency.isExternal());
        // An org unit and a reference item with the same ID are two employers.
        assertNotEquals(Employer.legalEntity("x"), Employer.external("x"));
        assertThrows(IllegalArgumentException.class, () -> new Employer(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Employer("company-a", "agency"));
    }
}
