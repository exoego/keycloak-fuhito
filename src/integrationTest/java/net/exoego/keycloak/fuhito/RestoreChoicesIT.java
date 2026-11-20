package net.exoego.keycloak.fuhito;

import java.util.List;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RestoreRulesIT.assertEachBreaks;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.at;
import static net.exoego.keycloak.fuhito.RestoreRulesIT.breaks;

class RestoreChoicesIT {

    private static final String CHOICE = "invalid.choice";

    private static final String PRIMARY = "invalid.primary_language";

    @Test
    void anUnknownChoiceFailsTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-choices", List.of(
                breaks("employment-terms", "t-1", "status", CHOICE,
                        at("/employments/0/terms/0", "{\"status\": \"RETIRED\"}")),
                breaks("reporting-lines", "rl-1", "kind", CHOICE,
                        at("/people", "{\"id\": \"p-2\", \"attributes\": {}}"),
                        at("", "{\"reportingLines\": [{\"id\": \"rl-1\", \"assignmentId\": \"a-1\", "
                                + "\"managerPersonId\": \"p-2\", \"kind\": \"BOSS\", "
                                + "\"validFrom\": \"2020-04-01\"}]}")),
                breaks("attribute-definitions", "d-1", "target", CHOICE,
                        at("/attributeDefinitions/0", "{\"target\": \"ROBOT\"}")),
                breaks("attribute-definitions", "d-1", "type", CHOICE,
                        at("/attributeDefinitions/0", "{\"type\": \"TEXT\"}")),
                breaks("external-ids", "x-1", "resourceType", CHOICE,
                        at("/externalIds/0", "{\"resourceType\": \"BUILDING\"}"))));
    }

    @Test
    void namesThatBreakARuleOfAWriteFailTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-names", List.of(
                breaks("people", "p-1", "primaryLanguage", PRIMARY, at("/people/0", "{\"primaryLanguage\": \"en\"}")),
                breaks("ref-kinds", "k-1", "primaryLanguage", PRIMARY,
                        at("/refKinds/0", "{\"primaryLanguage\": \"en\"}")),
                breaks("ref-item-names", "n-1", "primaryLanguage", PRIMARY,
                        at("/refItems/0/versions/0", "{\"primaryLanguage\": \"en\"}")),
                breaks("org-unit-versions", "v-1", "primaryLanguage", PRIMARY,
                        at("/orgUnits/0/versions/0", "{\"primaryLanguage\": \"en\"}")),
                breaks("ref-kinds", "k-1", "primaryLanguage", "invalid.required",
                        at("/refKinds/0", "{\"primaryLanguage\": null}")),
                // A person may have no names, but not names with no primary language.
                breaks("people", "p-1", "primaryLanguage", "invalid.required",
                        at("/people/0", "{\"primaryLanguage\": null}")),
                breaks("org-unit-versions", "v-1", "primaryLanguage", "invalid.language_tag",
                        at("/orgUnits/0/versions/0", "{\"primaryLanguage\": \"x_y\"}")),
                breaks("people", "p-1", "names.JA", "invalid.duplicate_language",
                        at("/people/0", "{\"names\": {\"ja\": {\"name\": \"山田\"}, "
                                + "\"JA\": {\"name\": \"山田\"}}}")),
                breaks("ref-item-names", "n-1", "names", "invalid.required",
                        at("/refItems/0/versions/0", "{\"names\": null}")),
                breaks("ref-kinds", "k-1", "names.ja", "invalid.required",
                        at("/refKinds/0", "{\"names\": {\"ja\": null}}")),
                breaks("org-unit-versions", "v-1", "names.ja.name", "invalid.required",
                        at("/orgUnits/0/versions/0", "{\"names\": {\"ja\": {}}}")),
                breaks("people", "p-1", "names.ja.name", "invalid.name",
                        at("/people/0", "{\"names\": {\"ja\": {\"name\": \"" + "山".repeat(256) + "\"}}}"))));
    }
}
