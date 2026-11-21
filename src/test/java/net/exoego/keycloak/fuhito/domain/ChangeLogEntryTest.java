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
    void eachDerivedValueSaysWhatItIsReadFromAndWhichWritesChangeIt() {
        assertEquals(List.of("KEYCLOAK_ORGANIZATION_ID"),
                Arrays.stream(DerivedValue.values()).map(Enum::name).toList());
        for (DerivedValue value : DerivedValue.values()) {
            assertFalse(value.field().isBlank(), value.name());
            assertFalse(value.readFrom().isBlank(), value.name());
            assertFalse(value.changedBy().isBlank(), value.name());
        }
        DerivedValue link = DerivedValue.KEYCLOAK_ORGANIZATION_ID;
        assertEquals(List.of("FUHITO_ORG_UNIT", "fuhito/org-units/u1", "keycloakOrganizationId"),
                List.of(link.resourceType().name(), link.path("u1"), link.field()));
    }
}
