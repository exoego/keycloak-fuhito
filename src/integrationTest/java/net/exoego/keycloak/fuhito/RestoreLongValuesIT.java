package net.exoego.keycloak.fuhito;

import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RealmExportIT.named;
import static net.exoego.keycloak.fuhito.RestoreMissingValuesIT.assertBadValue;
import static net.exoego.keycloak.fuhito.RestoreMissingValuesIT.withPerson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RestoreLongValuesIT {

    @Test
    void aValueLongerThanItsColumnFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = TestRealm.create("restore-long");
        ObjectNode longCode = withPerson();
        longCode.putArray("hierarchies").addObject().put("id", "h-1").put("code", "c".repeat(100))
                .put("name", "Project");
        ObjectNode longSource = withPerson();
        named(((ArrayNode) longSource.get("people")).addObject().put("id", "p-2").put("source", "s".repeat(100)))
                .putObject("attributes");

        assertBadValue(target.api, longCode, Map.of("list", "hierarchies", "id", "h-1", "field", "code"));
        assertBadValue(target.api, longSource, Map.of("list", "people", "id", "p-2", "field", "source"));

        // Not even the good person of each document was written.
        assertEquals(0, Api.json(target.api.get("/export/people")).size());
        assertFalse(Api.json(target.api.get("/export/hierarchies")).findValuesAsText("id").contains("h-1"));
    }
}
