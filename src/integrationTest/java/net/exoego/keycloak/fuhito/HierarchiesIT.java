package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HierarchiesIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;

    @BeforeAll
    static void createRealms() {
        realm = TestRealm.create("hierarchies");
        otherRealm = TestRealm.create("hierarchies");
        api = realm.api;
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void createsReadsRenamesAndDeletesAHierarchy() throws Exception {
        JsonNode matrix = created(api.post("/hierarchies", "{\"code\": \"matrix\", \"name\": \"マトリクス\"}"));
        String id = matrix.get("id").asText();

        assertEquals("matrix", matrix.get("code").asText());
        assertEquals(matrix, Api.json(api.get("/hierarchies/" + id)));
        assertEquals(List.of("default", "matrix"), Api.json(api.get("/hierarchies")).findValuesAsText("code"));
        assertEquals(List.of("matrix"), Api.json(api.get("/hierarchies?first=1&max=1")).findValuesAsText("code"));

        JsonNode renamed = Api.json(api.put("/hierarchies/" + id, "{\"name\": \"プロジェクト\"}"));
        assertEquals("プロジェクト", renamed.get("name").asText());
        assertEquals("matrix", renamed.get("code").asText());

        assertEquals(204, api.delete("/hierarchies/" + id).statusCode());
        assertEquals("hierarchy.not_found", errorOf(404, api.get("/hierarchies/" + id)));
        assertEquals(List.of("CREATE", "UPDATE", "DELETE"),
                Api.json(api.get("/change-log?resourceType=FUHITO_HIERARCHY&resourceId=" + id)).findValuesAsText("operation"));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        created(api.post("/hierarchies", "{\"code\": \"taken\", \"name\": \"x\"}"));

        assertEquals("hierarchy.code_taken", errorOf(409, api.post("/hierarchies", "{\"code\": \"taken\", \"name\": \"y\"}")));
        assertEquals("hierarchy.code_taken", errorOf(409, api.post("/hierarchies", "{\"code\": \"default\", \"name\": \"y\"}")));
        assertEquals(400, api.post("/hierarchies", "{\"code\": \"no name\"}").statusCode());
        assertEquals(400, api.post("/hierarchies", "{\"name\": \"no code\"}").statusCode());
        assertEquals(400, api.post("/hierarchies", "").statusCode());
        assertEquals(404, api.put("/hierarchies/nope", "{\"name\": \"x\"}").statusCode());
        assertEquals(404, api.delete("/hierarchies/nope").statusCode());
        assertEquals(401, new Api(realm.name, null).get("/hierarchies").statusCode());
    }

    @Test
    void theDefaultHierarchyCannotBeDeletedAndOneRealmDoesNotSeeAnother() throws Exception {
        String defaultId = Api.json(api.get("/hierarchies")).get(0).get("id").asText();
        String mine = created(api.post("/hierarchies", "{\"code\": \"mine\", \"name\": \"x\"}")).get("id").asText();

        assertEquals("hierarchy.default", errorOf(409, api.delete("/hierarchies/" + defaultId)));
        assertEquals(200, api.put("/hierarchies/" + defaultId, "{\"name\": \"本務\"}").statusCode());
        assertEquals(404, otherRealm.api.get("/hierarchies/" + mine).statusCode());
        assertEquals(404, otherRealm.api.put("/hierarchies/" + mine, "{\"name\": \"x\"}").statusCode());
        assertEquals(404, otherRealm.api.delete("/hierarchies/" + mine).statusCode());
        assertFalse(Api.json(otherRealm.api.get("/hierarchies")).findValuesAsText("code").contains("mine"));
        // The same code is free in another realm.
        created(otherRealm.api.post("/hierarchies", "{\"code\": \"mine\", \"name\": \"x\"}"));
    }
}
