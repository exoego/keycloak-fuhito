package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.ImportIT.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A text of an import row fits its column, in JSON and in CSV. An emoji is one character in the column, though Java
 * holds it in two chars.
 */
class ImportLengthsIT {

    private static final String FITS = "😀".repeat(255);
    private static final String TOO_LONG = "😀".repeat(256);

    private static HttpResponse<String> csv(Api api, String list, String csv) throws Exception {
        return api.postBytes("/imports/csv/" + list + "?effectiveDate=2026-04-01", "text/csv; charset=UTF-8",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> errors(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        return Api.json(response).get("errors").findValuesAsText("error");
    }

    @Test
    void aJsonRowFitsItsColumnByCharacters() throws Exception {
        TestRealm realm = TestRealm.create("import-lengths");
        String document = """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "changeOrders": [{"externalId": "O-1", "title": "4月の異動", "externalReference": "%s"}],
                 "orgUnits": [{"externalId": "%s", "primaryLanguage": "ja", "names": {"ja": {"name": "X病院"}}}]}
                """;

        HttpResponse<String> response = realm.api.post("/imports", document.formatted(TOO_LONG, TOO_LONG));

        assertEquals(List.of("invalid.too_long", "external_id.bad_value"), errors(response));
        JsonNode errors = Api.json(response).get("errors");
        assertEquals(List.of("changeOrders", "orgUnits"), errors.findValuesAsText("list"));
        assertEquals("externalReference", errors.get(0).get("params").get("field").asText());
        assertEquals("255", errors.get(0).get("params").get("max").asText());
        assertEquals("0", rows(realm, "fuhito_change_order"));

        ok(realm.api.post("/imports", document.formatted(FITS, FITS)));
        assertEquals(FITS, Api.json(realm.api.get("/change-orders")).get(0).get("externalReference").asText());
        String unit = Api.json(realm.api.get("/org-units?asOf=2026-04-01")).get(0).get("id").asText();
        assertEquals(List.of("sheet=" + FITS),
                Api.externalIds(ok(realm.api.get("/org-units/" + unit + "?asOf=2026-04-01"))));
    }

    @Test
    void aCsvCellFitsItsColumnByCharacters() throws Exception {
        Api api = TestRealm.create("csv-lengths").api;
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String orders = "externalId,title,externalReference\r\nO-1,4月の異動,%s\r\n";
        String ids = "resourceType,resourceId,source,externalId\r\nORG_UNIT," + ward + ",payroll,%s\r\n";

        assertEquals(List.of("invalid.too_long"), errors(csv(api, "change-orders", orders.formatted(TOO_LONG))));
        assertEquals(List.of("external_id.bad_value"), errors(csv(api, "external-ids", ids.formatted(TOO_LONG))));

        assertEquals(List.of(), ok(csv(api, "change-orders", orders.formatted(FITS))).get("errors")
                .findValuesAsText("error"));
        assertEquals(List.of(), ok(csv(api, "external-ids", ids.formatted(FITS))).get("errors")
                .findValuesAsText("error"));
        assertEquals(FITS, Api.json(api.get("/change-orders")).get(0).get("externalReference").asText());
        assertEquals(List.of("payroll=" + FITS),
                Api.externalIds(ok(api.get("/org-units/" + ward + "?asOf=2020-04-01"))));
    }
}
