package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportIT {

    private static final String HOSPITAL = """
            {"effectiveDate": "2026-04-01", "source": "sheet", %s
             "attributeDefinitions": [{"externalId": "beds", "target": "ORG_UNIT", "name": "beds", "type": "NUMBER"}],
             "refItems": [
               {"externalId": "UNIT_TYPE.hospital", "kind": "UNIT_TYPE", "code": "hospital", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}, "en": {"name": "Hospital"}}},
               {"externalId": "UNIT_TYPE.department", "kind": "UNIT_TYPE", "code": "department", "primaryLanguage": "ja", "names": {"ja": {"name": "部"}}}
             ],
             "orgUnits": [
               {"externalId": "W1", "primaryLanguage": "ja", "names": {"ja": {"name": "1病棟", "phoneticName": "いちびょうとう"}}, "parentExternalId": "N"},
               {"externalId": "N", "primaryLanguage": "ja", "names": {"ja": {"name": "看護部"}}, "unitType": "department", "parentExternalId": "H", "displayOrder": 2},
               {"externalId": "M", "primaryLanguage": "ja", "names": {"ja": {"name": "診療部"}}, "unitType": "department", "parentExternalId": "H", "displayOrder": 1},
               {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "X病院"}}, "unitType": "hospital", "code": "x-hospital",
                "validFrom": "1998-04-01", "attributes": {"beds": 120}}
             ]}
            """;

    static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static List<String> names(Api api, String asOf) throws Exception {
        return Api.names(api.tree("asOf=" + asOf));
    }

    static String rows(TestRealm realm, String table) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realm.id() + "'");
    }

    @Test
    void importsTheNamesOfAReferenceItemFromTheirDates() throws Exception {
        TestRealm realm = TestRealm.create("import-dated-names");
        Api api = realm.api;
        String document = """
                {"effectiveDate": "2026-04-01", "refItems": [
                  {"externalId": "JOB_TITLE.m1", "kind": "JOB_TITLE", "code": "m1", "primaryLanguage": "ja", "names": {"ja": {"name": "課長"}}, "validFrom": "2015-04-01"},
                  {"externalId": "JOB_TITLE.m1", "kind": "JOB_TITLE", "code": "m1", "primaryLanguage": "ja", "names": {"ja": {"name": "マネージャー"}, "en": {"name": "Manager"}}, "validFrom": "2023-04-01"}
                ]}
                """;

        JsonNode first = ok(api.post("/imports", document));
        JsonNode again = ok(api.post("/imports", document));

        assertEquals(1, first.get("counts").get("refItems").get("created").asInt());
        assertEquals(1, first.get("counts").get("refItems").get("changed").asInt());
        assertEquals(2, again.get("counts").get("refItems").get("unchanged").asInt());
        String id = Api.json(api.get("/ref-items?kind=JOB_TITLE")).get(0).get("id").asText();
        JsonNode versions = Api.json(api.get("/ref-items/" + id + "/history")).get("versions");
        assertEquals(List.of("課長", "マネージャー"), Api.names(versions));
        assertEquals(List.of("2015-04-01", "2023-04-01"), versions.findValuesAsText("validFrom"));
        assertEquals("課長", Api.json(api.get("/ref-items/" + id + "?asOf=2020-01-01")).get("name").asText());
        assertEquals("マネージャー", Api.json(api.get("/ref-items/" + id + "?asOf=2023-04-01")).get("name").asText());
        assertEquals("Manager", Api.json(api.withAcceptLanguage("en").get("/ref-items/" + id + "?asOf=2023-04-01"))
                .get("name").asText());
    }

    @Test
    void importsAHospitalThenTheSameDocumentThenAChangedOne() throws Exception {
        TestRealm realm = TestRealm.create("import");
        Api api = realm.api;

        JsonNode first = ok(api.post("/imports", HOSPITAL.formatted("")));

        assertTrue(first.get("applied").asBoolean());
        assertFalse(first.get("dryRun").asBoolean());
        assertEquals(0, first.get("errors").size());
        assertEquals(2, first.get("counts").get("refItems").get("created").asInt());
        assertEquals(4, first.get("counts").get("orgUnits").get("created").asInt());
        assertEquals(List.of("X病院", "診療部", "看護部", "1病棟"), names(api, "2026-04-01"));
        assertEquals(List.of("X病院"), names(api, "1998-04-01"));
        JsonNode root = Api.json(api.get("/org-units?roots=true&asOf=2026-04-01")).get(0);
        assertEquals(List.of("sheet=H"), Api.externalIds(root));
        assertEquals("sheet", root.get("source").asText());
        assertEquals("hospital", root.get("unitType").asText());
        assertEquals("x-hospital", root.get("code").asText());
        assertEquals(120, root.get("attributes").get("beds").asInt());

        JsonNode again = ok(api.post("/imports", HOSPITAL.formatted("")));
        assertTrue(again.get("applied").asBoolean());
        assertEquals(2, again.get("counts").get("refItems").get("unchanged").asInt());
        assertEquals(4, again.get("counts").get("orgUnits").get("unchanged").asInt());
        assertEquals(0, again.get("counts").get("orgUnits").get("changed").asInt());
        assertEquals("4", rows(realm, "fuhito_org_unit_version"));

        // In April 2027 the ward gets a new name and moves, and a new department appears.
        JsonNode changed = ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "source": "sheet", "orgUnits": [
                  {"externalId": "W1", "primaryLanguage": "ja", "names": {"ja": {"name": "東病棟", "phoneticName": "ひがしびょうとう"}}, "parentExternalId": "M"},
                  {"externalId": "R", "primaryLanguage": "ja", "names": {"ja": {"name": "リハビリ部"}}, "unitType": "department", "parentExternalId": "H", "displayOrder": 3},
                  {"externalId": "N", "primaryLanguage": "ja", "names": {"ja": {"name": "看護部"}}, "unitType": "department", "parentExternalId": "H", "displayOrder": 2}
                ]}
                """));
        assertEquals(1, changed.get("counts").get("orgUnits").get("created").asInt());
        assertEquals(1, changed.get("counts").get("orgUnits").get("changed").asInt());
        assertEquals(1, changed.get("counts").get("orgUnits").get("unchanged").asInt());
        assertEquals(List.of("X病院", "診療部", "看護部", "1病棟"), names(api, "2027-03-31"));
        assertEquals(List.of("X病院", "診療部", "東病棟", "看護部", "リハビリ部"), names(api, "2027-04-01"));
    }

    @Test
    void importsAHierarchyAndTheParentsInIt() throws Exception {
        TestRealm realm = TestRealm.create("import-parents");
        String document = """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "hierarchies": [{"externalId": "project", "code": "project", "name": "プロジェクト"}],
                 "orgUnits": [{"externalId": "P", "primaryLanguage": "ja", "names": {"ja": {"name": "P"}}}, {"externalId": "C", "primaryLanguage": "ja", "names": {"ja": {"name": "C"}}}],
                 "orgUnitParents": [{"hierarchy": "project", "orgUnitExternalId": "C", "parentExternalId": "P"}]}
                """;

        JsonNode first = ok(realm.api.post("/imports", document));
        JsonNode again = ok(realm.api.post("/imports", document));

        assertEquals(1, first.get("counts").get("orgUnitParents").get("changed").asInt(), first.toString());
        assertEquals(1, again.get("counts").get("orgUnitParents").get("unchanged").asInt(), again.toString());
        JsonNode roots = Api.json(realm.api.get("/org-units?roots=true&asOf=2026-04-01&hierarchy=project"));
        assertEquals(List.of("P"), Api.names(roots));
        assertEquals(List.of("C"), Api.names(Api.json(realm.api.get("/org-units/" + roots.get(0).get("id").asText()
                + "/children?asOf=2026-04-01&hierarchy=project"))));
    }

    @Test
    void aDryRunChecksEverythingAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("import-dry-run");

        JsonNode report = ok(realm.api.post("/imports", HOSPITAL.formatted("\"dryRun\": true,")));

        assertTrue(report.get("dryRun").asBoolean());
        assertFalse(report.get("applied").asBoolean());
        assertEquals(4, report.get("counts").get("orgUnits").get("created").asInt());
        assertEquals(0, report.get("errors").size());
        for (String table : List.of("fuhito_ref_item", "fuhito_org_unit", "fuhito_org_unit_version",
                "fuhito_org_unit_link", "fuhito_change_log")) {
            assertEquals("0", rows(realm, table), table);
        }
        // Only the default hierarchy, which the realm has from its start.
        assertEquals("1", rows(realm, "fuhito_hierarchy"));
        assertEquals(List.of(), names(realm.api, "2026-04-01"));
    }

    @Test
    void badRowsAreAllReportedAndNothingIsWritten() throws Exception {
        TestRealm realm = TestRealm.create("import-errors");

        HttpResponse<String> response = realm.api.withAcceptLanguage("ja").post("/imports", """
                {"effectiveDate": "2026-04-01", "orgUnits": [
                  {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "X病院"}}},
                  {"externalId": "A", "primaryLanguage": "ja", "names": {"ja": {"name": " "}}, "parentExternalId": "H"},
                  {"externalId": "B", "primaryLanguage": "ja", "names": {"ja": {"name": "B"}}, "parentExternalId": "NOPE"},
                  {"externalId": "C", "primaryLanguage": "ja", "names": {"ja": {"name": "C"}}, "parentExternalId": "A"}
                ]}
                """);

        assertEquals(400, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        assertFalse(report.get("applied").asBoolean());
        assertEquals(List.of("1", "2", "3"), report.get("errors").findValuesAsText("row"));
        assertEquals(List.of("invalid.blank", "import.parent_unknown", "import.parent_failed"),
                report.get("errors").findValuesAsText("error"));
        assertEquals("orgUnits", report.get("errors").get(0).get("list").asText());
        assertEquals("NOPE", report.get("errors").get(1).get("params").get("parentExternalId").asText());
        assertEquals("外部 ID「NOPE」の親は、取り込みデータにも保存済みデータにもありません。",
                report.get("errors").get(1).get("message").asText());
        assertEquals(1, report.get("counts").get("orgUnits").get("created").asInt());
        assertEquals("0", rows(realm, "fuhito_org_unit"));
        assertEquals("0", rows(realm, "fuhito_change_log"));
    }

    @Test
    void anImportUnderAChangeOrderLogsEveryWrite() throws Exception {
        TestRealm realm = TestRealm.create("import-order");
        Api api = realm.api;
        String order = Api.json(api.post("/change-orders",
                "{\"title\": \"初期データ登録\", \"effectiveDate\": \"2026-04-01\"}")).get("id").asText();

        ok(api.post("/imports?changeOrder=" + order, HOSPITAL.formatted("")));

        JsonNode changes = Api.json(api.get("/change-orders/" + order + "/changes"));
        assertEquals(List.of("FUHITO_ATTRIBUTE_DEFINITION", "FUHITO_REF_ITEM", "FUHITO_REF_ITEM", "FUHITO_ORG_UNIT",
                "FUHITO_ORG_UNIT", "FUHITO_ORG_UNIT", "FUHITO_ORG_UNIT"), changes.findValuesAsText("resourceType"));
        assertEquals(List.of("CREATE", "CREATE", "CREATE", "CREATE", "CREATE", "CREATE", "CREATE"),
                changes.findValuesAsText("operation"));
        // A parent is written before its children, wherever it is in the document.
        List<String> units = new ArrayList<>();
        for (int i = 3; i < 7; i++) {
            units.add(ok(api.get("/org-units/" + changes.get(i).get("resourceId").asText() + "?asOf=2026-04-01"))
                    .get("name").asText());
        }
        assertEquals(List.of("X病院", "看護部", "1病棟", "診療部"), units);
    }

    @Test
    void loadsASmallHospitalWithStaffThenATransferAndAResignation() throws Exception {
        TestRealm realm = TestRealm.withEmployer("import-people");
        Api api = realm.api;
        String nurse = realm.user("nurse");
        String clerk = realm.user("clerk");

        JsonNode first = ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "refItems": [
                   {"externalId": "OCCUPATION.nurse", "kind": "OCCUPATION", "code": "nurse", "primaryLanguage": "ja", "names": {"ja": {"name": "看護師"}}},
                   {"externalId": "EMPLOYMENT_TYPE.part-time", "kind": "EMPLOYMENT_TYPE", "code": "part-time", "primaryLanguage": "ja", "names": {"ja": {"name": "非常勤"}}},
                   {"externalId": "END_REASON.resignation", "kind": "END_REASON", "code": "resignation", "primaryLanguage": "ja", "names": {"ja": {"name": "退職"}}}
                 ],
                 "orgUnits": [
                   {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "X病院"}}},
                   {"externalId": "W1", "primaryLanguage": "ja", "names": {"ja": {"name": "1病棟"}}, "parentExternalId": "H"},
                   {"externalId": "W2", "primaryLanguage": "ja", "names": {"ja": {"name": "2病棟"}}, "parentExternalId": "H"}
                 ],
                 "employments": [
                   {"externalId": "E-nurse", "username": "nurse", "staffNumber": "N-001", "validFrom": "2020-04-01",
                    "employmentType": "part-time", "workPercent": 50},
                   {"externalId": "E-clerk", "username": "clerk"}
                 ],
                 "assignments": [
                   {"externalId": "A1", "employmentExternalId": "E-nurse", "orgUnitExternalId": "W1",
                    "occupation": "nurse", "primary": true},
                   {"externalId": "A2", "employmentExternalId": "E-clerk", "orgUnitExternalId": "H", "primary": true}
                 ]}
                """));
        assertTrue(first.get("applied").asBoolean());
        assertEquals(2, first.get("counts").get("employments").get("created").asInt());
        assertEquals(2, first.get("counts").get("assignments").get("created").asInt());

        JsonNode ofNurse = Api.json(api.get("/users/" + nurse + "/assignments?asOf=2026-04-01"));
        assertEquals("1病棟", ofNurse.get(0).get("orgUnitName").asText());
        assertEquals("N-001", ofNurse.get(0).get("staffNumber").asText());
        assertEquals("part-time", ofNurse.get(0).get("employmentType").asText());
        assertEquals("2020-04-01", Api.json(api.get("/users/" + nurse + "/history")).get("employments").get(0).get("validFrom").asText());

        // In April 2027 the nurse moves to ward 2, and the clerk resigns.
        String second = """
                {"effectiveDate": "2027-04-01", "source": "sheet",
                 "employments": [
                   {"externalId": "E-clerk", "username": "clerk", "validTo": "2027-04-01", "endReason": "resignation"}
                 ],
                 "assignments": [
                   {"externalId": "A3", "employmentExternalId": "E-nurse", "orgUnitExternalId": "W2",
                    "occupation": "nurse", "primary": true},
                   {"externalId": "A1", "employmentExternalId": "E-nurse", "orgUnitExternalId": "W1",
                    "occupation": "nurse", "primary": true, "validTo": "2027-04-01"}
                 ]}
                """;
        JsonNode moved = ok(api.post("/imports", second));
        assertEquals(1, moved.get("counts").get("employments").get("changed").asInt());
        assertEquals(1, moved.get("counts").get("assignments").get("created").asInt());
        assertEquals(1, moved.get("counts").get("assignments").get("changed").asInt());

        assertEquals("1病棟", Api.json(api.get("/users/" + nurse + "/assignments?asOf=2027-03-31")).get(0).get("orgUnitName").asText());
        assertEquals("2病棟", Api.json(api.get("/users/" + nurse + "/assignments?asOf=2027-04-01")).get(0).get("orgUnitName").asText());
        assertEquals(0, Api.json(api.get("/users/" + clerk + "/assignments?asOf=2027-04-01")).size());
        JsonNode clerkEmployment = Api.json(api.get("/users/" + clerk + "/history")).get("employments").get(0);
        assertEquals("2027-04-01", clerkEmployment.get("validTo").asText());
        assertEquals("resignation", clerkEmployment.get("endReason").asText());

        JsonNode again = ok(api.post("/imports", second));
        assertEquals(1, again.get("counts").get("employments").get("unchanged").asInt());
        assertEquals(2, again.get("counts").get("assignments").get("unchanged").asInt());
    }

    static String lookup(Api api, String resourceType, String externalId) throws Exception {
        return ok(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=sheet&externalId="
                + externalId)).get("resourceId").asText();
    }

    @Test
    void anImportSavesTheLinkToTheEmploymentThatARecordContinues() throws Exception {
        TestRealm realm = TestRealm.create("import-continues");
        Api api = realm.api;
        realm.user("sato");
        realm.user("suzuki");
        ok(api.post("/imports", """
                {"effectiveDate": "2020-04-01", "source": "sheet",
                 "refItems": [{"externalId": "UNIT_TYPE.company", "kind": "UNIT_TYPE", "code": "company", "primaryLanguage": "ja", "names": {"ja": {"name": "会社"}}, "legalEntity": true}],
                 "orgUnits": [
                   {"externalId": "X", "primaryLanguage": "ja", "names": {"ja": {"name": "医療法人X会"}}, "unitType": "company"},
                   {"externalId": "Y", "primaryLanguage": "ja", "names": {"ja": {"name": "株式会社Yサービス"}}, "unitType": "company"},
                   {"externalId": "K", "primaryLanguage": "ja", "names": {"ja": {"name": "栄養科"}}, "parentExternalId": "X"},
                   {"externalId": "L", "primaryLanguage": "ja", "names": {"ja": {"name": "給食課"}}, "parentExternalId": "Y"}
                 ],
                 "employments": [
                   {"externalId": "E1", "username": "sato", "employerExternalId": "X", "validTo": "2026-04-01"},
                   {"externalId": "E3", "username": "suzuki", "employerExternalId": "X", "validTo": "2026-04-01"}
                 ],
                 "assignments": [
                   {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "K", "primary": true},
                   {"externalId": "A3", "employmentExternalId": "E3", "orgUnitExternalId": "K", "primary": true}
                 ]}
                """));
        String e3 = lookup(api, "EMPLOYMENT", "E3");
        String a3 = lookup(api, "ASSIGNMENT", "A3");
        // Sato is named by the external IDs of the records that the new ones continue, and Suzuki by their IDs.
        String transfer = """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "employments": [
                   {"externalId": "E2", "username": "sato", "employerExternalId": "Y",
                    "continuesEmploymentExternalId": "E1"},
                   {"externalId": "E4", "username": "suzuki", "employerExternalId": "Y",
                    "continuesEmploymentId": "%s"}
                 ],
                 "assignments": [
                   {"externalId": "A2", "employmentExternalId": "E2", "orgUnitExternalId": "L", "primary": true},
                   {"externalId": "A4", "employmentExternalId": "E4", "orgUnitExternalId": "L", "primary": true}
                 ]}
                """.formatted(e3);

        JsonNode first = ok(api.post("/imports", transfer));

        assertEquals(2, first.get("counts").get("employments").get("created").asInt(), first.toString());
        assertEquals(2, first.get("counts").get("assignments").get("created").asInt(), first.toString());
        assertEquals(lookup(api, "EMPLOYMENT", "E1"), ok(api.get("/employments/" + lookup(api, "EMPLOYMENT", "E2")))
                .path("continuesEmploymentId").asText());
        assertEquals(e3, ok(api.get("/employments/" + lookup(api, "EMPLOYMENT", "E4")))
                .path("continuesEmploymentId").asText());
        // Each new assignment starts on the end of the old one, which ends with its employment.
        assertEquals(List.of(lookup(api, "ASSIGNMENT", "A1")), ok(api.get("/assignments/"
                + lookup(api, "ASSIGNMENT", "A2"))).get("endedOnStart").findValuesAsText("assignmentId"));
        assertEquals(List.of(a3), ok(api.get("/assignments/" + lookup(api, "ASSIGNMENT", "A4")))
                .get("endedOnStart").findValuesAsText("assignmentId"));

        // The stored links are the ones of the rows, so the same document changes nothing.
        JsonNode again = ok(api.post("/imports", transfer));
        assertEquals(2, again.get("counts").get("employments").get("unchanged").asInt(), again.toString());
        assertEquals(2, again.get("counts").get("assignments").get("unchanged").asInt(), again.toString());
    }

    @Test
    void badPeopleRowsAreReportedAndNothingIsWritten() throws Exception {
        TestRealm realm = TestRealm.withEmployer("import-people-errors");
        realm.user("nurse");

        HttpResponse<String> response = realm.api.post("/imports", """
                {"effectiveDate": "2026-04-01",
                 "orgUnits": [{"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "X病院"}}}],
                 "employments": [
                   {"externalId": "E1", "username": "nurse"},
                   {"externalId": "E2", "username": "nobody"}
                 ],
                 "assignments": [
                   {"externalId": "A1", "employmentExternalId": "E2", "orgUnitExternalId": "H"},
                   {"externalId": "A2", "employmentExternalId": "E1", "orgUnitExternalId": "NOPE"}
                 ]}
                """);

        assertEquals(400, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        assertEquals(List.of("import.username_unknown", "import.employment_unknown", "import.org_unit_unknown"),
                report.get("errors").findValuesAsText("error"));
        assertEquals(List.of("employments", "assignments", "assignments"), report.get("errors").findValuesAsText("list"));
        // The realm has its legal entity, with the two entries of the change log for it, and nothing more.
        assertEquals("1", rows(realm, "fuhito_org_unit"));
        assertEquals("2", rows(realm, "fuhito_change_log"));
        for (String table : List.of("fuhito_employment", "fuhito_assignment")) {
            assertEquals("0", rows(realm, table), table);
        }
    }

    @Test
    void rejectsABadDocument() throws Exception {
        TestRealm realm = TestRealm.create("import-bad");

        HttpResponse<String> noDate = realm.api.post("/imports", "{\"orgUnits\": []}");
        assertEquals(400, noDate.statusCode());
        assertEquals("invalid.required", Api.json(noDate).get("error").asText());
        assertEquals(401, new Api(realm.name, null).post("/imports", "{}").statusCode());
    }

    @Test
    void anImportNamesTheEmployerInARealmWithSeveralLegalEntities() throws Exception {
        TestRealm realm = TestRealm.create("import-employers");
        Api api = realm.api;
        String sato = realm.user("sato");
        String suzuki = realm.user("suzuki");
        String document = """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "refItems": [{"externalId": "UNIT_TYPE.company", "kind": "UNIT_TYPE", "code": "company", "primaryLanguage": "ja", "names": {"ja": {"name": "会社"}}, "legalEntity": true}],
                 "orgUnits": [
                   {"externalId": "X", "primaryLanguage": "ja", "names": {"ja": {"name": "医療法人X会"}}, "unitType": "company"},
                   {"externalId": "Y", "primaryLanguage": "ja", "names": {"ja": {"name": "株式会社Yサービス"}}, "unitType": "company"},
                   {"externalId": "K", "primaryLanguage": "ja", "names": {"ja": {"name": "栄養科"}}, "parentExternalId": "X"}
                 ],
                 "employments": [
                   {"externalId": "E1", "username": "sato", "staffNumber": "1001", "employerExternalId": "X"},
                   {"externalId": "E2", "username": "suzuki", "staffNumber": "1001", "employerExternalId": "Y"}
                 ],
                 "assignments": [
                   {"externalId": "A2", "employmentExternalId": "E2", "orgUnitExternalId": "K", "primary": true}
                 ]}
                """;

        JsonNode first = ok(api.post("/imports", document));

        assertTrue(first.get("applied").asBoolean());
        assertEquals(2, first.get("counts").get("employments").get("created").asInt());
        JsonNode entities = Api.json(api.get("/org-units?legalEntity=true&asOf=2026-04-01"));
        assertEquals(List.of("医療法人X会", "株式会社Yサービス"), Api.names(entities).stream().sorted().toList());
        String employerOfSato = Api.json(api.get("/users/" + sato + "/history")).get("employments").get(0).get("employerId").asText();
        String employerOfSuzuki = Api.json(api.get("/users/" + suzuki + "/history")).get("employments").get(0).get("employerId").asText();
        assertEquals("医療法人X会", Api.json(api.get("/org-units/" + employerOfSato + "?asOf=2026-04-01")).get("name").asText());
        assertEquals("株式会社Yサービス", Api.json(api.get("/org-units/" + employerOfSuzuki + "?asOf=2026-04-01")).get("name").asText());

        // The same document changes nothing. A unit type row with no mark keeps the mark.
        JsonNode again = ok(api.post("/imports", document.replace(", \"legalEntity\": true", "")));
        assertEquals(0, again.get("counts").get("employments").get("created").asInt());
        assertEquals(1, again.get("counts").get("refItems").get("unchanged").asInt());
        assertEquals(2, again.get("counts").get("employments").get("unchanged").asInt());
        assertEquals(2, Api.json(api.get("/org-units?legalEntity=true&asOf=2026-04-01")).size());

        HttpResponse<String> bad = api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "employments": [
                   {"externalId": "E1", "username": "sato", "staffNumber": "1001", "employerExternalId": "Y"},
                   {"externalId": "E3", "username": "sato", "validFrom": "2027-04-01"},
                   {"externalId": "E4", "username": "sato", "validFrom": "2027-04-01", "employerExternalId": "nope"},
                   {"externalId": "E5", "username": "sato", "validFrom": "2027-04-01", "employerExternalId": "K"}
                 ]}
                """);
        assertEquals(400, bad.statusCode(), bad.body());
        assertEquals(List.of("import.employer_mismatch", "employment.employer_required", "import.org_unit_unknown",
                "employment.employer_not_legal_entity"), Api.json(bad).get("errors").findValuesAsText("error"));
        assertEquals("2", rows(realm, "fuhito_employment"));
    }
}
