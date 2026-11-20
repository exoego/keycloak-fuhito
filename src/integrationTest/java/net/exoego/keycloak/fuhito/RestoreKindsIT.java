package net.exoego.keycloak.fuhito;

import java.util.List;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RestoreRulesIT.assertEachBreaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.at;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.breaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.item;

class RestoreKindsIT {

    private static final String UNKNOWN = "ref_item.unknown";

    private static final String END = "{\"validTo\": \"2030-04-01\"}";

    // role-1 is a unit role, and corp is a unit type.
    @Test
    void aFieldThatNamesAnItemOfAnotherKindFailsTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-item-kinds", List.of(
                breaks("org-unit-versions", "v-2", "unitTypeId", "org_unit.unit_type_unknown",
                        at("/orgUnits/1/versions/0", "{\"unitTypeId\": \"role-1\"}")),
                breaks("org-unit-versions", "v-2", "workLocationId", UNKNOWN,
                        at("/orgUnits/1/versions/0", "{\"workLocationId\": \"role-1\"}")),
                breaks("org-unit-versions", "v-2", "costCenterId", UNKNOWN,
                        at("/orgUnits/1/versions/0", "{\"costCenterId\": \"role-1\"}")),
                breaks("employments", "e-1", "externalEmployerId", "employment.external_employer_unknown",
                        at("/employments/0", "{\"employerId\": null, \"externalEmployerId\": \"role-1\"}")),
                breaks("employments", "e-1", "endReasonId", UNKNOWN,
                        at("/employments/0", "{\"validTo\": \"2030-04-01\", \"endReasonId\": \"role-1\"}"),
                        at("/employments/0/terms/0", END), at("/assignments/0/periods/0", END),
                        at("/unitRoles/0", END)),
                breaks("employment-terms", "t-1", "employmentTypeId", UNKNOWN,
                        at("/employments/0/terms/0", "{\"employmentTypeId\": \"role-1\"}")),
                breaks("employment-terms", "t-1", "gradeId", UNKNOWN,
                        at("/employments/0/terms/0", "{\"gradeId\": \"role-1\"}")),
                breaks("employment-terms", "t-1", "leaveReasonId", UNKNOWN,
                        at("/employments/0/terms/0", "{\"status\": \"ON_LEAVE\", \"leaveReasonId\": \"role-1\"}")),
                breaks("assignments", "r-1", "jobTitleId", UNKNOWN,
                        at("/assignments/0/periods/0", "{\"jobTitleId\": \"role-1\"}")),
                breaks("assignments", "r-1", "occupationId", UNKNOWN,
                        at("/assignments/0/periods/0", "{\"occupationId\": \"role-1\"}")),
                breaks("assignments", "r-1", "kindId", UNKNOWN,
                        at("/assignments/0/periods/0", "{\"kindId\": \"role-1\"}")),
                breaks("assignments", "r-1", "workLocationId", UNKNOWN,
                        at("/assignments/0/periods/0", "{\"workLocationId\": \"role-1\"}")),
                breaks("assignments", "r-1", "costCenterId", UNKNOWN,
                        at("/assignments/0/periods/0", "{\"costCenterId\": \"role-1\"}")),
                breaks("unit-roles", "ur-1", "roleId", UNKNOWN, at("/unitRoles/0", "{\"roleId\": \"corp\"}")),
                breaks("qualifications", "q-1", "kindId", UNKNOWN,
                        at("", "{\"qualifications\": [{\"id\": \"q-1\", \"personId\": \"p-1\", \"kindId\": \"role-1\", "
                                + "\"validFrom\": \"2020-04-01\", \"attributes\": {}}]}"))));
    }

    // The export has the kind site.
    @Test
    void anUnknownKindOfReferenceDataFailsTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-kinds", List.of(
                breaks("ref-items", "x", "kind", "invalid.kind", at("/refItems", item("x", "BUILDING", ""))),
                breaks("attribute-definitions", "d-2", "refKind", "invalid.choice",
                        at("/attributeDefinitions", "{\"id\": \"d-2\", \"target\": \"PERSON\", \"name\": \"place\", "
                                + "\"type\": \"REF_ITEM\", \"refKind\": \"BUILDING\"}")),
                breaks("attribute-definitions", "d-1", "refKind", "attribute.ref_kind",
                        at("/attributeDefinitions/0", "{\"type\": \"REF_ITEM\"}")),
                breaks("attribute-definitions", "d-1", "refKind", "attribute.ref_kind",
                        at("/attributeDefinitions/0", "{\"refKind\": \"site\"}"))));
    }
}
