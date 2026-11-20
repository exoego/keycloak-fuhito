package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;

    @BeforeAll
    static void createRealms() {
        realm = TestRealm.withEmployer("people");
        otherRealm = TestRealm.withEmployer("people");
        api = realm.api;
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void createsReadsReplacesAndDeletesAPersonWithNoUser() throws Exception {
        realm.attribute("PERSON", "派遣元", "STRING");
        JsonNode person = created(api.post("/people", """
                {"primaryLanguage": "ja", "names": {
                  "ja": {"name": "山田 太郎", "phoneticName": "やまだ たろう"},
                  "EN-us": {"name": "Taro Yamada"}},
                 "attributes": {"派遣元": "A社"}, "externalId": "P-1", "source": "hr"}
                """));
        String id = person.get("id").asText();

        assertFalse(person.has("userId"));
        // With no Accept-Language, a read gives the primary names.
        assertEquals("山田 太郎", person.get("name").asText());
        assertEquals("やまだ たろう", person.get("phoneticName").asText());
        assertEquals("RECORD", person.get("nameSource").asText());
        assertEquals("ja", person.get("primaryLanguage").asText());
        assertEquals("Taro Yamada", person.get("names").get("en-US").get("name").asText());
        assertEquals("A社", person.get("attributes").get("派遣元").asText());
        assertEquals(List.of("hr=P-1"), Api.externalIds(person));
        assertFalse(person.has("externalId"));
        assertEquals("hr", person.get("source").asText());
        assertEquals(person, ok(api.get("/people/" + id)));
        // The first language of Accept-Language gives the names of that tag, else of that language with no region.
        Api english = api.withAcceptLanguage("en-US, ja;q=0.5");
        JsonNode inEnglish = ok(english.get("/people/" + id));
        assertEquals("Taro Yamada", inEnglish.get("name").asText());
        assertFalse(inEnglish.has("phoneticName"));
        assertEquals(person.get("names"), inEnglish.get("names"));
        JsonNode found = ok(english.get("/people?q=taro%20yamada"));
        assertEquals(1, found.size());
        assertEquals("Taro Yamada", found.get(0).get("name").asText());
        assertEquals("山田 太郎", ok(api.withAcceptLanguage("en").get("/people/" + id)).get("name").asText());

        JsonNode changed = ok(api.putWithVersion("/people/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}}"));
        assertEquals("山田 花子", changed.get("name").asText());
        assertEquals(1, changed.get("names").size());
        assertFalse(changed.has("phoneticName"));
        assertEquals(List.of("hr=P-1"), Api.externalIds(changed));
        // A replace with the ID of another system adds it. The person keeps the system that made it.
        JsonNode payroll = ok(api.putWithVersion("/people/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, \"source\": \"payroll\", "
                + "\"externalId\": \"E-5\"}"));
        assertEquals(List.of("hr=P-1", "payroll=E-5"), Api.externalIds(payroll));
        assertEquals("hr", payroll.get("source").asText());

        assertEquals(204, api.deleteWithVersion("/people/" + id).statusCode());
        assertEquals("person.not_found", errorOf(404, api.get("/people/" + id)));
        assertEquals(List.of("CREATE", "UPDATE", "UPDATE", "DELETE"),
                Api.json(api.get("/change-log?resourceType=FUHITO_PERSON&resourceId=" + id))
                        .findValuesAsText("operation"));
    }

    @Test
    void aPersonWithNoNameOfItsOwnShowsTheNameOfKeycloak() throws Exception {
        String user = realm.user("taro", "Taro", "Yamada");
        String nameless = realm.user("nameless");

        JsonNode person = created(api.post("/people", "{\"userId\": \"" + user + "\"}"));
        assertEquals(user, person.get("userId").asText());
        assertEquals("Taro Yamada", person.get("name").asText());
        assertEquals("KEYCLOAK", person.get("nameSource").asText());
        // The change log has no name.
        HttpResponse<String> log = api.get("/change-log?resourceType=FUHITO_PERSON&resourceId=" + person.get("id").asText());
        assertEquals(1, Api.json(log).size());
        assertFalse(log.body().contains("Taro"), log.body());

        assertFalse(person.has("primaryLanguage"));
        assertEquals(0, person.get("names").size());

        JsonNode noName = created(api.post("/people", "{\"userId\": \"" + nameless + "\"}"));
        assertFalse(noName.has("name"));
        assertFalse(noName.has("nameSource"));

        // A name of the record wins from then on.
        JsonNode named = ok(api.putWithVersion("/people/" + person.get("id").asText(), "{\"userId\": \"" + user
                + "\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 太郎\"}}}"));
        assertEquals("山田 太郎", named.get("name").asText());
        assertEquals("RECORD", named.get("nameSource").asText());

        JsonNode found = ok(api.get("/people?userId=" + user));
        assertEquals(List.of(person.get("id").asText()), found.findValuesAsText("id"));
        assertEquals(0, ok(api.get("/people?userId=nobody")).size());
        assertEquals(1, api.count("/people/count?userId=" + user));
        assertEquals(0, api.count("/people/count?userId=nobody"));
    }

    @Test
    void aRealmCanPutTheFamilyNameFirst() throws Exception {
        TestRealm japanese = TestRealm.withEmployer("people-order", Map.of("fuhito.nameOrder", "family-first"));
        String user = japanese.user("taro", "太郎", "山田");

        JsonNode person = created(japanese.api.post("/people", "{\"userId\": \"" + user + "\"}"));

        assertEquals("山田 太郎", person.get("name").asText());
        assertEquals("KEYCLOAK", person.get("nameSource").asText());
    }

    @Test
    void listsThePeopleInPages() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("people-list");
        for (String name : List.of("A", "B", "C")) {
            created(scratch.api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}}"));
        }

        List<String> all = ok(scratch.api.get("/people")).findValuesAsText("id");
        assertEquals(3, all.size());
        assertEquals(all.stream().sorted().toList(), all);
        assertEquals(all.subList(1, 3), ok(scratch.api.get("/people?first=1&max=5")).findValuesAsText("id"));
        assertEquals(all.subList(0, 1), ok(scratch.api.get("/people?max=1")).findValuesAsText("id"));
        assertEquals(0, ok(otherRealm.api.get("/people")).size());
        // The path segment count wins over the ID of a person, and the ID still reads the person.
        assertEquals(3, scratch.api.count("/people/count"));
        assertEquals(all.get(0), ok(scratch.api.get("/people/" + all.get(0))).get("id").asText());
        assertEquals(0, otherRealm.api.count("/people/count"));
    }

    @Test
    void theLinkHeaderLeadsThroughEveryPage() throws Exception {
        TestRealm scratch = TestRealm.create("people-cursor");
        for (String name : List.of("A", "B", "C", "D", "E")) {
            created(scratch.api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}}"));
        }
        List<String> all = ok(scratch.api.get("/people")).findValuesAsText("id");
        String prefix = "/admin/realms/" + scratch.name + "/fuhito";

        // A page that is not full has no link.
        assertTrue(scratch.api.get("/people").headers().firstValue("Link").isEmpty());

        List<String> followed = new ArrayList<>();
        String path = "/people?max=2";
        int pages = 0;
        while (path != null) {
            HttpResponse<String> response = scratch.api.get(path);
            assertEquals(200, response.statusCode(), response.body());
            followed.addAll(Api.json(response).findValuesAsText("id"));
            pages++;
            String link = response.headers().firstValue("Link").orElse(null);
            if (link == null) {
                path = null;
            } else {
                assertTrue(link.endsWith(">; rel=\"next\""), link);
                String url = link.substring(1, link.indexOf('>'));
                assertTrue(url.contains("after=people.v1__") && url.contains("max=2") && !url.contains("first="), url);
                path = url.substring(url.indexOf(prefix) + prefix.length());
            }
        }
        assertEquals(all, followed);
        // Two full pages, and one with the last person and no link.
        assertEquals(3, pages);

        // A cursor of another list, of another version, or with no name is rejected.
        for (String cursor : List.of("members.v1__u__1__2026-04-01__a", "people.v0__" + all.get(0), all.get(0))) {
            HttpResponse<String> bad = scratch.api.get("/people?after=" + cursor);
            assertEquals(400, bad.statusCode(), bad.body());
            assertEquals("invalid.cursor", Api.json(bad).get("error").asText());
            assertTrue(Api.json(bad).get("message").asText().contains("people.v1"), bad.body());
        }
        assertEquals(all.subList(1, 5), ok(scratch.api.get("/people?after=people.v1__" + all.get(0))).findValuesAsText("id"));
        HttpResponse<String> both = scratch.api.get("/people?first=1&after=people.v1__" + all.get(0));
        assertEquals(400, both.statusCode(), both.body());
        assertEquals("invalid.cursor_with_first", Api.json(both).get("error").asText());
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String user = realm.user("linked");
        String id = created(api.post("/people", "{\"userId\": \"" + user
                + "\", \"externalId\": \"P-9\", \"source\": \"hr\"}")).get("id").asText();
        String other = created(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Other\"}}}")).get("id").asText();

        assertEquals("person.name_required", errorOf(400, api.post("/people", "{}")));
        assertEquals("person.user_unknown", errorOf(400, api.post("/people", "{\"userId\": \"nobody\"}")));
        assertEquals("person.user_taken", errorOf(409, api.post("/people", "{\"userId\": \"" + user + "\"}")));
        assertEquals("person.user_taken", errorOf(409, api.putWithVersion("/people/" + other,
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Other\"}}, \"userId\": \"" + user
                        + "\"}")));
        assertEquals("external_id.taken", errorOf(409, api.post("/people",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X\"}}, \"externalId\": \"P-9\", "
                        + "\"source\": \"hr\"}")));
        // The names need a primary language, which must be one of them.
        assertEquals("invalid.required", errorOf(400, api.post("/people", "{\"names\": {\"ja\": {\"name\": \"X\"}}}")));
        HttpResponse<String> notAKey = api.post("/people", "{\"primaryLanguage\": \"en\", \"names\": {\"ja\": "
                + "{\"name\": \"X\"}}}");
        assertEquals("invalid.primary_language", errorOf(400, notAKey));
        assertTrue(Api.json(notAKey).get("message").asText().contains("\"en\""), notAKey.body());
        HttpResponse<String> blank = api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \" \"}}}");
        assertEquals("invalid.blank", errorOf(400, blank));
        assertTrue(Api.json(blank).get("message").asText().contains("names.ja.name"), blank.body());
        HttpResponse<String> phonetic = api.putWithVersion("/people/" + other, "{\"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"Other\", \"phoneticName\": \"\"}}}");
        assertEquals("invalid.blank", errorOf(400, phonetic));
        assertEquals("names.ja.phoneticName", Api.json(phonetic).get("params").get("field").asText());
        assertEquals("invalid.blank", errorOf(400, api.post("/people", "{\"userId\": \" \"}")));
        assertEquals("invalid.required", errorOf(400, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X\"}}, \"externalId\": \"P-8\"}")));
        assertEquals("person.not_found", errorOf(404, otherRealm.api.get("/people/" + id)));
        assertEquals("person.not_found", errorOf(404, otherRealm.api.putWithVersion("/people/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X\"}}}")));
        assertEquals("person.not_found", errorOf(404, otherRealm.api.deleteWithVersion("/people/" + id)));
        assertEquals(401, new Api(realm.name, null).get("/people").statusCode());
        assertEquals(401, new Api(realm.name, null).post("/people", "{}").statusCode());
    }

    @Test
    void anEmptyListFailsInTheAttributesOfAPerson() throws Exception {
        realm.textList("PERSON", "languages");
        String person = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"空のリスト\"}}, "
                + "\"attributes\": {\"languages\": %s}}";
        HttpResponse<String> create = api.post("/people", person.formatted("[]"));
        assertEquals("invalid.blank", errorOf(400, create));
        assertEquals("attributes.languages", Api.json(create).get("params").get("field").asText());

        String id = created(api.post("/people", person.formatted("[\"ja\"]"))).get("id").asText();
        HttpResponse<String> replace = api.putWithVersion("/people/" + id, person.formatted("[]"));
        assertEquals("invalid.blank", errorOf(400, replace));
        assertEquals("attributes.languages", Api.json(replace).get("params").get("field").asText());
        assertEquals("ja", ok(api.get("/people/" + id)).get("attributes").get("languages").get(0).asText());
    }

    @Test
    void findsPeopleByNameAndByStaffNumber() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("people-search");
        Api scratchApi = scratch.api;
        String unit = created(scratchApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String hanako = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"山田 花子\", \"phoneticName\": \"やまだ はなこ\"}, \"en\": {\"name\": \"Hanako Yamada\"}}}"))
                .get("id").asText();
        created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Other\"}}}"));
        // A person who takes the name of the Keycloak user.
        String user = scratch.user("tsuzuki", "Ichiro", "Tsuzuki");
        JsonNode staff = created(scratchApi.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + unit
                + "\", \"staffNumber\": \"S-77\", \"validFrom\": \"2020-04-01\"}"));
        String ichiro = staff.get("employment").get("personId").asText();

        for (Map.Entry<String, String> found : Map.of("%E3%82%84%E3%81%BE%E3%81%A0", hanako, "HANAKO", hanako,
                "ichiro", ichiro, "TSUZUKI", ichiro).entrySet()) {
            assertEquals(List.of(found.getValue()), ok(scratchApi.get("/people?q=" + found.getKey()))
                    .findValuesAsText("id"), found.getKey());
        }
        assertEquals(List.of(ichiro), ok(scratchApi.get("/people?staffNumber=S-77")).findValuesAsText("id"));
        assertEquals(List.of(), ok(scratchApi.get("/people?q=hanako&staffNumber=S-77")).findValuesAsText("id"));
        assertEquals(1, scratchApi.count("/people/count?staffNumber=S-77"));
        assertEquals(1, scratchApi.count("/people/count?q=TSUZUKI"));
        assertEquals(0, scratchApi.count("/people/count?q=hanako&staffNumber=S-77"));
        assertEquals(3, scratchApi.count("/people/count?q=%20"));
        // The keys of the names in other languages are not names.
        assertEquals(List.of(), ok(scratchApi.get("/people?q=en")).findValuesAsText("id"));

        // A masked person has no name to find.
        assertEquals(200, scratchApi.postWithVersion("/people/" + hanako + "/mask", "").statusCode());
        assertEquals(List.of(), ok(scratchApi.get("/people?q=hanako")).findValuesAsText("id"));
    }

    @Test
    void maskingAUserClearsThePersonAndErasingRemovesIt() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("people-personal-data");
        Api scratchApi = scratch.api;
        String user = scratch.user("left");
        scratch.attribute("PERSON", "住所", "STRING");
        String kept = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Kept\"}}}")).get("id").asText();
        String id = created(scratchApi.post("/people", "{\"userId\": \"" + user + "\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"山田 太郎\", \"phoneticName\": \"やまだ\"}, \"en\": {\"name\": "
                + "\"Taro Yamada\"}}, \"attributes\": {\"住所\": \"東京都\"}, \"externalId\": \"P-1\", \"source\": \"hr\"}"))
                .get("id").asText();
        String log = "/change-log?resourceType=FUHITO_PERSON&resourceId=";

        assertEquals(200, scratchApi.post("/users/" + user + "/mask", "").statusCode());

        JsonNode masked = ok(scratchApi.get("/people/" + id));
        assertEquals(user, masked.get("userId").asText());
        assertEquals("hr", masked.get("source").asText());
        for (String field : List.of("name", "phoneticName", "primaryLanguage", "externalId")) {
            assertFalse(masked.has(field), field);
        }
        assertEquals(List.of(), Api.externalIds(masked));
        assertEquals(0, masked.get("names").size());
        assertEquals("0", KeycloakEnvironment.sql("select count(*) from fuhito_person_name where person_id = '"
                + id + "'"));
        assertEquals(0, masked.get("attributes").size());
        HttpResponse<String> maskedLog = scratchApi.get(log + id);
        assertEquals(1, Api.json(maskedLog).size());
        for (String secret : List.of("山田", "Taro", "やまだ", "東京都", "P-1")) {
            assertFalse(maskedLog.body().contains(secret), secret);
        }

        assertEquals(204, scratchApi.delete("/users/" + user + "/data").statusCode());

        assertEquals("person.not_found", errorOf(404, scratchApi.get("/people/" + id)));
        // The entries hold only IDs, so they stay.
        assertEquals(List.of("CREATE"), Api.json(scratchApi.get(log + id)).findValuesAsText("operation"));
        assertEquals(List.of("UPDATE", "DELETE"), Api.json(scratchApi.get(
                "/change-log?resourceType=FUHITO_PERSON_DATA&resourceId=" + id)).findValuesAsText("operation"));
        assertEquals("Kept", ok(scratchApi.get("/people/" + kept)).get("name").asText());
        assertEquals(1, Api.json(scratchApi.get(log + kept)).size());
    }

    @Test
    void aPersonWithNoUserCanBeEmployedAndAssigned() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("people-employed");
        Api scratchApi = scratch.api;
        String unit = created(scratchApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String cleaner = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"清掃 花子\"}}}")).get("id").asText();
        String nurse = scratch.user("nurse");

        JsonNode employment = created(scratchApi.post("/employments",
                "{\"personId\": \"" + cleaner + "\", \"validFrom\": \"2020-04-01\"}"));
        assertEquals(cleaner, employment.get("personId").asText());
        assertFalse(employment.has("userId"));
        JsonNode assignment = created(scratchApi.post("/assignments", "{\"employmentId\": \""
                + employment.get("id").asText() + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\", \"head\": true}"));
        assertFalse(assignment.has("userId"));
        // The staff shortcut takes a person too.
        String visitor = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"応援 医師\"}}}")).get("id").asText();
        JsonNode staff = created(scratchApi.post("/staff", "{\"personId\": \"" + visitor + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        assertEquals(visitor, staff.get("employment").get("personId").asText());
        created(scratchApi.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\"}"));

        JsonNode members = ok(scratchApi.get("/org-units/" + unit + "/members"));
        assertEquals(3, members.size());
        assertEquals(List.of(nurse), members.findValuesAsText("userId"));
        assertTrue(members.findValuesAsText("personId").containsAll(List.of(cleaner, visitor)));
        // The head has no user, so the derived manager of the nurse has no user ID.
        JsonNode managers = ok(scratchApi.get("/users/" + nurse + "/managers"));
        assertEquals(1, managers.size());
        assertFalse(managers.get(0).has("userId"));
        assertTrue(managers.get(0).get("derived").asBoolean());

        // The person gets an account later. The employment then shows the user.
        String account = scratch.user("cleaner");
        ok(scratchApi.putWithVersion("/people/" + cleaner, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"清掃 花子\"}}, \"userId\": \"" + account + "\"}"));
        assertEquals(account, ok(scratchApi.get("/employments/" + employment.get("id").asText())).get("userId").asText());
        assertEquals(List.of(unit), ok(scratchApi.get("/users/" + account + "/assignments")).findValuesAsText("orgUnitId"));
        assertEquals(List.of(account), ok(scratchApi.get("/users/" + nurse + "/managers")).findValuesAsText("userId"));

        assertEquals("person.in_use", errorOf(409, scratchApi.deleteWithVersion("/people/" + cleaner)));
    }

    @Test
    void aPersonWithNoUserCanHoldAQualificationAndBeAManager() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("people-lines");
        Api scratchApi = scratch.api;
        scratch.refItem("QUALIFICATION", "license", "免許");
        String unit = created(scratchApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"外科\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String visiting = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"応援 医師\"}}}")).get("id").asText();
        String advisor = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"顧問\"}}}")).get("id").asText();
        String resident = scratch.user("resident");

        JsonNode license = created(scratchApi.post("/qualifications", "{\"personId\": \"" + visiting
                + "\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}"));
        assertEquals(visiting, license.get("personId").asText());
        assertFalse(license.has("userId"));
        assertEquals("person.in_use", errorOf(409, scratchApi.deleteWithVersion("/people/" + visiting)));

        String assignment = created(scratchApi.post("/staff", "{\"userId\": \"" + resident + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}")).get("assignment").get("id").asText();
        JsonNode line = created(scratchApi.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerPersonId\": \"" + advisor + "\", \"validFrom\": \"2020-04-01\"}"));
        assertEquals(advisor, line.get("managerPersonId").asText());
        assertFalse(line.has("managerUserId"));
        assertEquals("person.in_use", errorOf(409, scratchApi.deleteWithVersion("/people/" + advisor)));

        JsonNode managers = ok(scratchApi.get("/users/" + resident + "/managers"));
        assertEquals(List.of(advisor), managers.findValuesAsText("personId"));
        assertFalse(managers.get(0).has("userId"));
        assertFalse(managers.get(0).get("derived").asBoolean());

        assertEquals("reporting_line.manager_unknown", errorOf(400, scratchApi.post("/reporting-lines",
                "{\"assignmentId\": \"" + assignment + "\", \"managerPersonId\": \"nobody\", "
                        + "\"validFrom\": \"2020-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, scratchApi.post("/reporting-lines",
                "{\"assignmentId\": \"" + assignment + "\", \"validFrom\": \"2020-04-01\"}")));
        assertEquals("qualification.person_unknown", errorOf(400, scratchApi.post("/qualifications",
                "{\"personId\": \"nobody\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}")));
    }

    @Test
    void anEmploymentByUserIdUsesThePersonOfTheUserOrCreatesIt() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("people-by-user");
        Api scratchApi = scratch.api;
        String user = scratch.user("taro", "Taro", "Yamada");
        String start = "{\"userId\": \"" + user + "\", \"validFrom\": \"";

        JsonNode first = created(scratchApi.post("/employments", start + "2020-04-01\", \"validTo\": \"2021-04-01\"}"));
        JsonNode second = created(scratchApi.post("/employments", start + "2022-04-01\"}"));

        String personId = first.get("personId").asText();
        assertEquals(user, first.get("userId").asText());
        assertEquals(personId, second.get("personId").asText());
        JsonNode people = ok(scratchApi.get("/people"));
        assertEquals(List.of(personId), people.findValuesAsText("id"));
        assertEquals("Taro Yamada", people.get(0).get("name").asText());
        assertEquals(List.of("CREATE"), Api.json(scratchApi.get("/change-log?resourceType=FUHITO_PERSON&resourceId="
                + personId)).findValuesAsText("operation"));

        assertEquals("employment.person_unknown", errorOf(400, scratchApi.post("/employments",
                "{\"personId\": \"nobody\", \"validFrom\": \"2020-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, scratchApi.post("/employments",
                "{\"validFrom\": \"2020-04-01\"}")));
        assertEquals("employment.person_unknown", errorOf(400, otherRealm.api.post("/employments",
                "{\"personId\": \"" + personId + "\", \"validFrom\": \"2020-04-01\"}")));

        // A new person of an import cannot take the user.
        HttpResponse<String> taken = scratchApi.post("/imports", "{\"effectiveDate\": \"2026-04-01\", "
                + "\"people\": [{\"externalId\": \"P-taro\", \"userId\": \"" + user + "\"}]}");
        assertEquals(400, taken.statusCode(), taken.body());
        JsonNode error = Api.json(taken).get("errors").get(0);
        assertEquals("import.user_taken", error.get("error").asText());
        assertEquals(personId, error.get("params").get("personId").asText());
    }

    @Test
    void aPersonWithNoUserIsReadMaskedAndErasedByPersonId() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("people-reads");
        Api scratchApi = scratch.api;
        scratch.refItem("QUALIFICATION", "license", "免許");
        String unit = created(scratchApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"外科\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String chief = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"部長\", \"phoneticName\": \"ぶちょう\"}}}"))
                .get("id").asText();
        String member = created(scratchApi.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"応援 医師\"}}, \"externalId\": \"X-1\", "
                + "\"source\": \"hr\"}")).get("id").asText();
        String start = "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2024-04-01\"";
        created(scratchApi.post("/staff", "{\"personId\": \"" + chief + start + ", \"head\": true}"));
        JsonNode staff = created(scratchApi.post("/staff", "{\"personId\": \"" + member + start
                + ", \"staffNumber\": \"D-1\"}"));
        created(scratchApi.post("/qualifications", "{\"personId\": \"" + member
                + "\", \"kind\": \"license\", \"number\": \"第1号\", \"validFrom\": \"2010-05-01\"}"));
        String people = "/people/" + member;

        assertEquals(List.of(unit),
                ok(scratchApi.get(people + "/assignments?asOf=2021-04-01&includePath=true")).findValuesAsText("orgUnitId"));
        assertEquals(0, ok(scratchApi.get(people + "/assignments")).size());
        assertEquals(List.of(chief), ok(scratchApi.get(people + "/managers?asOf=2021-04-01")).findValuesAsText("personId"));
        assertEquals(List.of(member),
                ok(scratchApi.get("/people/" + chief + "/reports?asOf=2021-04-01")).findValuesAsText("personId"));
        assertEquals(1, scratchApi.count("/people/" + chief + "/reports/count?asOf=2021-04-01"));
        assertEquals(0, scratchApi.count("/people/" + chief + "/reports/count?asOf=2024-04-01"));
        assertEquals(List.of("license"), ok(scratchApi.get(people + "/qualifications")).findValuesAsText("kind"));
        assertEquals(1, ok(scratchApi.get(people + "/qualifications?all=true")).size());
        JsonNode history = ok(scratchApi.get(people + "/history"));
        assertEquals(member, history.get("personId").asText());
        assertFalse(history.has("userId"));
        assertFalse(history.has("maskedAt"));
        assertEquals(List.of(staff.get("employment").get("id").asText()), history.get("employments").findValuesAsText("id"));
        assertEquals(1, history.get("assignments").size());

        JsonNode mask = ok(scratchApi.postWithVersion(people + "/mask", ""));
        assertEquals(member, mask.get("personId").asText());
        assertFalse(mask.has("userId"));
        JsonNode masked = ok(scratchApi.get(people));
        assertFalse(masked.has("name"));
        assertEquals(List.of(), Api.externalIds(masked));
        JsonNode maskedHistory = ok(scratchApi.get(people + "/history"));
        assertEquals(mask.get("maskedAt").asText(), maskedHistory.get("maskedAt").asText());
        assertFalse(maskedHistory.get("employments").get(0).has("staffNumber"));
        assertFalse(ok(scratchApi.get(people + "/qualifications?all=true")).get(0).has("number"));
        assertEquals(mask.get("maskedAt").asText(), ok(scratchApi.postWithVersion(people + "/mask", "")).get("maskedAt").asText());

        assertEquals(204, scratchApi.deleteWithVersion(people + "/data").statusCode());
        assertEquals("person.not_found", errorOf(404, scratchApi.get(people)));
        assertEquals("person.not_found", errorOf(404, scratchApi.deleteWithVersion(people + "/data")));
        assertEquals(404, scratchApi.get("/employments/" + staff.get("employment").get("id").asText()).statusCode());
        // The entries of the masks and of the erase stay.
        assertEquals(List.of("UPDATE", "UPDATE", "DELETE"), Api.json(scratchApi.get(
                "/change-log?resourceType=FUHITO_PERSON_DATA&resourceId=" + member)).findValuesAsText("operation"));
        assertEquals(List.of("CREATE"), Api.json(scratchApi.get("/change-log?resourceType=FUHITO_PERSON&resourceId="
                + member)).findValuesAsText("operation"));
        // The other person is untouched.
        assertEquals("部長", ok(scratchApi.get("/people/" + chief)).get("name").asText());
        assertEquals(1, ok(scratchApi.get("/people/" + chief + "/history")).get("employments").size());

        for (String path : List.of("/assignments", "/history", "/managers", "/reports", "/qualifications",
                "/reports/count", "/unit-roles/count")) {
            assertEquals("person.not_found", errorOf(404, scratchApi.get("/people/nobody" + path)));
            assertEquals("person.not_found", errorOf(404, otherRealm.api.get("/people/" + chief + path)));
        }
        assertEquals("person.not_found", errorOf(404, scratchApi.postWithVersion("/people/nobody/mask", "")));
        assertEquals("person.still_employed", errorOf(409, scratchApi.postWithVersion("/people/" + created(scratchApi.post("/staff",
                "{\"personId\": \"" + chief + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2025-04-01\"}"))
                .get("employment").get("personId").asText() + "/mask", "")));
        assertEquals("invalid.date", errorOf(400, scratchApi.get("/people/" + chief + "/managers?asOf=x")));
    }
}
