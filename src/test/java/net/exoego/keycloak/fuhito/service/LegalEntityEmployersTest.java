package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegalEntityEmployersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final OrgUnitService orgUnits = new OrgUnitService(store, new MemoryAssignmentStore(), refItems,
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));
    private final LegalEntityEmployers employers = new LegalEntityEmployers(store, refItems);

    @BeforeEach
    void defineTheUnitTypes() {
        refItems.insert(new RefItem("type-company", RefKind.UNIT_TYPE, "company", "Company", Map.of(), 0, Map.of(),
                true, true));
        refItems.insert(new RefItem("type-ward", RefKind.UNIT_TYPE, "ward", "Ward", Map.of(), 0, Map.of(), true));
        store.legalEntityTypes.add("type-company");
    }

    private String unit(String name, String unitType, String validFrom) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, unitType, null, validFrom, null,
                null, null, null)).unit().id();
    }

    private FuhitoException rejected(String requestedId, LocalDate date) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> employers.of(requestedId, date));
        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        return e;
    }

    @Test
    void anEmploymentEndsByTheEndOfItsEmployer() {
        String open = unit("Open", "company", "2020-04-01");
        String closing = unit("Closing", "company", "2020-04-01");
        orgUnits.end(closing, "2027-04-01");

        employers.requireCovers(open, ValidPeriod.startingAt(DAY));
        employers.requireCovers(closing, new ValidPeriod(DAY, LocalDate.of(2027, 4, 1)));
        for (ValidPeriod tooLong : List.of(ValidPeriod.startingAt(DAY), new ValidPeriod(DAY, LocalDate.of(2027, 4, 2)))) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> employers.requireCovers(closing, tooLong));
            assertEquals("employment.employer_ends", e.code());
            assertEquals(Map.of("employerId", closing, "date", "2027-04-01"), e.params());
        }
    }

    @Test
    void aRequestThatNamesNoEmployerGetsTheOnlyLegalEntity() {
        unit("Ward", "ward", "2020-04-01");
        unit("Untyped", null, "2020-04-01");
        assertEquals("employment.no_legal_entity", rejected(null, DAY).code());

        String company = unit("Company", "company", "2020-04-01");
        assertEquals(company, employers.of(null, DAY));
        // The company does not exist yet on this date.
        assertEquals("employment.no_legal_entity", rejected(null, LocalDate.of(2019, 4, 1)).code());

        unit("Second", "company", "2027-04-01");
        assertEquals(company, employers.of(null, DAY));
        assertEquals("employment.employer_required", rejected(null, LocalDate.of(2027, 4, 1)).code());
    }

    @Test
    void aNamedEmployerMustBeALegalEntityOnTheDate() {
        String company = unit("Company", "company", "2020-04-01");
        String second = unit("Second", "company", "2020-04-01");
        String ward = unit("Ward", "ward", "2020-04-01");
        String untyped = unit("Untyped", null, "2020-04-01");

        assertEquals(company, employers.of(company, DAY));
        assertEquals(second, employers.of(second, DAY));
        FuhitoException notOne = rejected(ward, DAY);
        assertEquals("employment.employer_not_legal_entity", notOne.code());
        assertEquals("Ward", notOne.params().get("name"));
        assertEquals("employment.employer_not_legal_entity", rejected(untyped, DAY).code());
        assertEquals("employment.employer_unknown", rejected("nope", DAY).code());
        assertEquals("employment.employer_unknown", rejected(company, LocalDate.of(2019, 4, 1)).code());

        // The ward becomes a company of its own in 2027.
        orgUnits.changeDetails(ward, new OrgUnitChange("2027-04-01", null, "Ward Inc.", null, null, null, null, "company",
                null, null));
        assertEquals(ward, employers.of(ward, LocalDate.of(2027, 4, 1)));
        assertEquals("employment.employer_not_legal_entity", rejected(ward, LocalDate.of(2027, 3, 31)).code());
    }
}
