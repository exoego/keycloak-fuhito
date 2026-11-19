package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RealmGroupProjectionIT {

    @Test
    void mirrorsEveryRootBelowTheRealmGroup() throws Exception {
        TestRealm realm = TestRealm.withEmployer("realm-groups", Map.of("fuhito.projection.enabled", "true",
                "fuhito.projection.realmGroup", "/org"));
        Api api = realm.api;
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        RealmResource keycloak = admin.realm(realm.name);
        GroupRepresentation org = new GroupRepresentation();
        org.setName("org");
        String orgId = CreatedResponseUtil.getCreatedId(keycloak.groups().add(org));

        String hospital = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String nurse = realm.user("nurse");
        HttpResponse<String> staff = api.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + ward
                + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, staff.statusCode(), staff.body());

        HttpResponse<String> report = api.post("/projection/rebuild", "");

        assertEquals(200, report.statusCode(), report.body());
        assertEquals(1, Api.json(report).get("realmGroupTrees").asInt());
        // The employer of the realm is a root too, so it has a group of its own.
        List<String> roots = keycloak.groups().group(orgId).getSubGroups(0, 100, false).stream()
                .map(GroupRepresentation::getName).sorted().toList();
        assertEquals(List.of("法人", "病院"), roots);
        GroupRepresentation hospitalGroup = keycloak.groups().group(orgId).getSubGroups(0, 100, false).stream()
                .filter(group -> group.getName().equals("病院")).findFirst().orElseThrow();
        GroupRepresentation wardGroup = keycloak.groups().group(hospitalGroup.getId()).getSubGroups(0, 100, false)
                .getFirst();
        assertEquals("1病棟", wardGroup.getName());
        assertEquals(List.of(ward), wardGroup.getAttributes().get("fuhito.orgUnitId"));
        assertEquals(List.of(nurse), keycloak.groups().group(wardGroup.getId()).members().stream()
                .map(member -> member.getId()).toList());
        assertEquals(0, Api.json(api.post("/projection/rebuild", "")).get("groupsCreated").asInt());

        // The last run can be read later, for a caller whose call a proxy ended.
        JsonNode run = Api.json(api.get("/projection/last-run"));
        assertEquals("DONE", run.get("state").asText());
        assertEquals(1, run.get("report").get("realmGroupTrees").asInt());
        assertEquals("projection", run.get("job").asText());
    }

    @Test
    void aPathThatNamesNoGroupFailsTheRebuild() throws Exception {
        TestRealm realm = TestRealm.create("realm-groups-missing", Map.of("fuhito.projection.enabled", "true",
                "fuhito.projection.realmGroup", "/nowhere"));
        assertEquals(404, realm.api.get("/projection/last-run").statusCode());

        HttpResponse<String> report = realm.api.post("/projection/rebuild", "");

        assertEquals(409, report.statusCode(), report.body());
        assertEquals("projection.realm_group_unknown", Api.json(report).get("error").asText());
        JsonNode run = Api.json(realm.api.get("/projection/last-run"));
        assertEquals("FAILED", run.get("state").asText());
        assertEquals("projection.realm_group_unknown", run.get("error").asText());
    }
}
