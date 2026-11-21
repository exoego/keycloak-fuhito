package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.PersonRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportRowReaderTest {

    @Test
    void tellsAKeyThatARowLeavesOutFromAKeyWithNull() {
        OrgUnitRow row = ImportRows.read(OrgUnitRow.class, """
                {"externalId": "W", "code": null, "unitType": "ward", "displayOrder": 2,
                 "names": {"ja": {"name": "病棟", "shortName": null}, "en": null},
                 "attributes": {"beds": 10, "floor": null}, "successorIds": ["A", "B"]}
                """);

        assertEquals("W", row.externalId());
        assertNull(row.code());
        assertEquals("ward", row.unitType());
        assertEquals(2, row.displayOrder());
        assertEquals(new OrgUnitName("病棟", null, null), row.names().get("ja"));
        assertTrue(row.names().containsKey("en"));
        assertNull(row.names().get("en"));
        assertEquals(10, row.attributes().get("beds"));
        assertTrue(row.attributes().containsKey("floor"));
        assertEquals(List.of("A", "B"), row.successorIds());
        assertEquals(Set.of("code", "names.ja.shortName", "names.en", "attributes.floor"), row.nulls());
        assertTrue(row.gives("code", row.code()));
        assertFalse(row.gives("timeZone", row.timeZone()));
        assertTrue(row.isNull("code"));
        assertEquals("kept", row.or("timeZone", row.timeZone(), "kept"));
        assertNull(row.or("code", row.code(), "kept"));
    }

    // The steps of the path of the error, such as orgUnits, [1], and names.
    private static List<String> unknownKey(Class<?> type, String json) {
        UnrecognizedPropertyException error = assertThrows(UnrecognizedPropertyException.class,
                () -> new ObjectMapper().readValue(json, type));
        return error.getPath().stream()
                .map(step -> step.getFieldName() == null ? "[" + step.getIndex() + "]" : step.getFieldName())
                .toList();
    }

    @Test
    void aKeyNamedLikeTheKeysWithNullFails() {
        assertEquals(List.of("nulls"), unknownKey(OrgUnitRow.class, """
                {"externalId": "W", "nulls": ["code"], "parentId": null}
                """));
    }

    @Test
    void aKeyThatTheRowDoesNotHaveFails() {
        UnrecognizedPropertyException error = assertThrows(UnrecognizedPropertyException.class,
                () -> new ObjectMapper().readValue("{\"externalId\": \"W\", \"parentExternalID\": \"H\"}",
                        OrgUnitRow.class));

        assertEquals("parentExternalID", error.getPropertyName());
        assertTrue(error.getKnownPropertyIds().contains("parentExternalId"), error.getMessage());
        assertFalse(error.getKnownPropertyIds().contains("nulls"), error.getMessage());
        assertEquals(List.of("orgUnitId"), unknownKey(PersonRow.class, """
                {"externalId": "P", "orgUnitId": "W"}
                """));
    }

    @Test
    void aKeyOfANameThatItDoesNotHaveFailsWithTheKeyOfTheRowInItsPath() {
        assertEquals(List.of("names", "ja", "shortname"), unknownKey(OrgUnitRow.class, """
                {"externalId": "W", "names": {"ja": {"name": "病棟", "shortname": "病"}}}
                """));
        assertEquals(List.of("orgUnits", "[1]", "names", "ja", "shortname"), unknownKey(ImportDocument.class, """
                {"effectiveDate": "2026-04-01",
                 "orgUnits": [{"externalId": "V"}, {"externalId": "W", "names": {"ja": {"shortname": "病"}}}]}
                """));
    }

    @Test
    void aNumberTooLargeForItsFieldFailsWithTheKeyOfTheRowInItsPath() {
        JsonMappingException error = assertThrows(JsonMappingException.class, () -> new ObjectMapper().readValue("""
                {"effectiveDate": "2026-04-01",
                 "orgUnits": [{"externalId": "V"}, {"externalId": "W", "displayOrder": 2147483648}]}
                """, ImportDocument.class));

        assertEquals(List.of("orgUnits", "[1]", "displayOrder"), error.getPath().stream()
                .map(step -> step.getFieldName() == null ? "[" + step.getIndex() + "]" : step.getFieldName())
                .toList());
        assertTrue(error.getCause() instanceof InputCoercionException, error.toString());
    }

    @Test
    void readsTheRowsOfADocumentAndANullRow() throws Exception {
        ImportDocument document = new ObjectMapper().readValue("""
                {"effectiveDate": "2026-04-01", "orgUnits": [{"externalId": "W", "timeZone": null}, null],
                 "people": [{"externalId": "P", "names": {"ja": {"name": "山田", "phoneticName": null}}}]}
                """, ImportDocument.class);

        assertEquals(Set.of("timeZone"), document.orgUnits().getFirst().nulls());
        assertNull(document.orgUnits().get(1));
        assertEquals(Set.of("names.ja.phoneticName"), document.people().getFirst().nulls());
    }

    @Test
    void aRowThatIsNoObjectFails() {
        ObjectMapper json = new ObjectMapper();

        assertThrows(MismatchedInputException.class, () -> json.readValue("\"W\"", OrgUnitRow.class));
        assertThrows(MismatchedInputException.class, () -> json.readValue("[\"W\"]", OrgUnitRow.class));
    }
}
