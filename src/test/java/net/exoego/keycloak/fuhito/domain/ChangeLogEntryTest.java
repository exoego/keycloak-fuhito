package net.exoego.keycloak.fuhito.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChangeLogEntryTest {

    private static final Instant AT = Instant.parse("2026-04-01T00:00:00Z");

    @Test
    void aWriteHasItsUserAndNoCause() {
        ChangeLogEntry entry = ChangeLogEntry.write("e1", AT, "admin", ResourceType.FUHITO_ORG_UNIT, "u1", "CREATE",
                "fuhito/org-units/u1", "order");

        assertEquals(new ChangeLogEntry("e1", 0, AT, ChangeLogEntry.Kind.WRITE, "admin",
                ResourceType.FUHITO_ORG_UNIT, "u1", "CREATE", "fuhito/org-units/u1", "order", null, null), entry);
    }

    @Test
    void aPropagatedChangeHasNoUserAndThePathOfItsResource() {
        ChangeLogEntry entry = ChangeLogEntry.propagated("e2", AT, DerivedValue.KEYCLOAK_ORGANIZATION_ID, "u1",
                ChangeLogEntry.Cause.WRITE, 41L, "order");

        assertEquals(new ChangeLogEntry("e2", 0, AT, ChangeLogEntry.Kind.PROPAGATED, null,
                ResourceType.FUHITO_ORG_UNIT, "u1", "UPDATE", "fuhito/org-units/u1", "order",
                ChangeLogEntry.Cause.WRITE, 41L), entry);
    }

    @Test
    void aWriteMakesThePropagatedChangeOfAReadThatItChangedWithItsTimeAndItsChangeOrder() {
        ChangeLogEntry write = new ChangeLogEntry("e1", 41, AT, ChangeLogEntry.Kind.WRITE, "admin",
                ResourceType.FUHITO_PERSON, "p1", "UPDATE", "fuhito/people/p1", "order", null, null);

        assertEquals(ChangeLogEntry.propagated("e2", AT, DerivedValue.ASSIGNMENT_USER_ID, "a1",
                ChangeLogEntry.Cause.WRITE, 41L, "order"),
                write.causing("e2", new PropagatedChange(DerivedValue.ASSIGNMENT_USER_ID, "a1")));
    }

    @Test
    void eachDerivedValueSaysWhatItIsReadFromAndWhichWritesChangeIt() {
        assertEquals(List.of("KEYCLOAK_ORGANIZATION_ID", "EMPLOYMENT_USER_ID", "ASSIGNMENT_USER_ID",
                "ASSIGNMENT_ENDED_ON_START", "QUALIFICATION_USER_ID", "UNIT_ROLE_USER_ID",
                "REPORTING_LINE_MANAGER_USER_ID", "PERSON_NAME"),
                Arrays.stream(DerivedValue.values()).map(Enum::name).toList());
        for (DerivedValue value : DerivedValue.values()) {
            assertFalse(value.field().isBlank(), value.name());
            assertFalse(value.readFrom().isBlank(), value.name());
            assertFalse(value.changedBy().isBlank(), value.name());
        }
        DerivedValue link = DerivedValue.KEYCLOAK_ORGANIZATION_ID;
        assertEquals(List.of("FUHITO_ORG_UNIT", "fuhito/org-units/u1", "keycloakOrganizationId"),
                List.of(link.resourceType().name(), link.path("u1"), link.field()));
        DerivedValue manager = DerivedValue.REPORTING_LINE_MANAGER_USER_ID;
        assertEquals(List.of("FUHITO_REPORTING_LINE", "fuhito/reporting-lines/l1", "managerUserId"),
                List.of(manager.resourceType().name(), manager.path("l1"), manager.field()));
        DerivedValue ended = DerivedValue.ASSIGNMENT_ENDED_ON_START;
        assertEquals(List.of("FUHITO_ASSIGNMENT", "fuhito/assignments/a1", "endedOnStart"),
                List.of(ended.resourceType().name(), ended.path("a1"), ended.field()));
        DerivedValue name = DerivedValue.PERSON_NAME;
        assertEquals(List.of("FUHITO_PERSON", "fuhito/people/p1", "name"),
                List.of(name.resourceType().name(), name.path("p1"), name.field()));
    }
}
