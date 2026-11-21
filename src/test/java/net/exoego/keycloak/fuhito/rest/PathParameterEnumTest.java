package net.exoego.keycloak.fuhito.rest;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import net.exoego.keycloak.fuhito.service.CsvExportService;
import net.exoego.keycloak.fuhito.service.RealmExport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathParameterEnumTest {

    // An annotation takes no constant list, so each enum of the document repeats a set of the code.
    private static final Map<String, Set<String>> TAKEN = Map.of(
            "GET /export/{list} list", Set.copyOf(RealmExport.LISTS),
            "GET /imports/csv/{list} list", Set.copyOf(CsvExportService.LISTS),
            "POST /imports/csv/{list} list", ImportsResource.CSV_LISTS.keySet());

    @Test
    void theEnumOfEachPathParameterIsTheSetThatTheCodeTakes() throws Exception {
        JsonNode document = new ObjectMapper(new YAMLFactory())
                .readTree(new File(System.getProperty("openapi.document")));
        JsonNode components = document.get("components").get("parameters");
        Map<String, Set<String>> enums = new TreeMap<>();

        for (Map.Entry<String, JsonNode> path : document.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                for (JsonNode parameter : operation.getValue().path("parameters")) {
                    if (parameter.has("$ref")) {
                        String ref = parameter.get("$ref").asText();
                        parameter = components.get(ref.substring(ref.lastIndexOf('/') + 1));
                    }
                    JsonNode values = parameter.path("schema").path("enum");
                    if (!parameter.path("in").asText().equals("path") || !values.isArray()) {
                        continue;
                    }
                    String key = operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey() + " "
                            + parameter.get("name").asText();
                    Set<String> set = new HashSet<>();
                    values.forEach(value -> set.add(value.asText()));
                    assertEquals(values.size(), set.size(), key);
                    enums.put(key, set);
                }
            }
        }
        assertEquals(TAKEN, enums);
    }
}
