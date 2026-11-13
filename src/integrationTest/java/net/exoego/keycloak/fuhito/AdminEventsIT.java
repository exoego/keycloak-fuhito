package net.exoego.keycloak.fuhito;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.AdminEventRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminEventsIT {

    @Test
    void everySuccessfulWriteRecordsAnAdminEvent() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var realm = new RealmRepresentation();
        realm.setRealm("events-" + UUID.randomUUID());
        realm.setEnabled(true);
        realm.setAdminEventsEnabled(true);
        realm.setAdminEventsDetailsEnabled(true);
        admin.realms().create(realm);
        Api api = new Api(realm.getRealm(), admin.tokenManager().getAccessTokenString());

        String typeId = Api.json(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"dept\", \"name\": \"部\"}")).get("id").asText();
        api.put("/ref-items/" + typeId, "{\"name\": \"部門\"}");
        String parentId = Api.json(api.post("/org-units", "{\"name\": \"本部\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String unitId = Api.json(api.post("/org-units", "{\"name\": \"経理部\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        api.put("/org-units/" + unitId, "{\"name\": \"財務部\", \"validFrom\": \"2027-04-01\"}");
        api.put("/org-units/" + unitId + "/parent", "{\"parentId\": \"" + parentId + "\", \"validFrom\": \"2028-04-01\"}");
        api.put("/org-units/" + unitId + "/end", "{\"validTo\": \"2029-04-01\"}");
        // Failed requests and reads record nothing.
        assertEquals(409, api.put("/org-units/" + unitId + "/end", "{\"validTo\": \"2030-04-01\"}").statusCode());
        assertEquals(400, api.post("/org-units", "{\"name\": \" \", \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(200, api.get("/org-units?asOf=2026-04-01").statusCode());
        assertEquals(204, api.delete("/ref-items/" + typeId).statusCode());

        List<AdminEventRepresentation> events = admin.realm(realm.getRealm()).getAdminEvents().stream()
                .filter(e -> e.getResourceType().startsWith("FUHITO_"))
                .toList();

        // Two events can have the same time, so the order is not compared.
        assertEquals(Stream.of(
                "CREATE FUHITO_REF_ITEM fuhito/ref-items/" + typeId,
                "UPDATE FUHITO_REF_ITEM fuhito/ref-items/" + typeId,
                "DELETE FUHITO_REF_ITEM fuhito/ref-items/" + typeId,
                "CREATE FUHITO_ORG_UNIT fuhito/org-units/" + parentId,
                "CREATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId,
                "UPDATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId,
                "UPDATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId + "/parent",
                "UPDATE FUHITO_ORG_UNIT fuhito/org-units/" + unitId + "/end").sorted().toList(),
                events.stream()
                        .map(e -> e.getOperationType() + " " + e.getResourceType() + " " + e.getResourcePath())
                        .sorted()
                        .toList());
        AdminEventRepresentation rename = events.stream()
                .filter(e -> e.getOperationType().equals("UPDATE")
                        && e.getResourcePath().equals("fuhito/org-units/" + unitId))
                .findFirst()
                .orElseThrow();
        assertTrue(rename.getRepresentation().contains("財務部"), rename.getRepresentation());
        assertTrue(events.stream().allMatch(e -> e.getAuthDetails().getUserId() != null));
    }
}
