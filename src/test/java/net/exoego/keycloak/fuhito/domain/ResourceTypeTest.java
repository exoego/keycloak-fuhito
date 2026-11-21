package net.exoego.keycloak.fuhito.domain;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceTypeTest {

    @Test
    void theOpenApiDocumentListsEveryTypeThatTheChangeLogWrites() throws Exception {
        JsonNode paths = new ObjectMapper(new YAMLFactory())
                .readTree(new File(System.getProperty("openapi.document")))
                .get("paths");
        List<String> types = Arrays.stream(ResourceType.values()).map(ResourceType::name).toList();

        for (String path : List.of("/change-log", "/change-log/count")) {
            List<String> listed = new ArrayList<>();
            for (JsonNode parameter : paths.get(path).get("get").get("parameters")) {
                if (parameter.get("name").asText().equals("resourceType")) {
                    parameter.get("schema").get("enum").forEach(type -> listed.add(type.asText()));
                }
            }
            assertEquals(types, listed, path);
        }
    }
}
