package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calls the admin REST API of the extension for one realm with one token.
 */
final class Api {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // A body with a version added keeps its numbers as they were written.
    private static final ObjectMapper BODIES = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    // The first part of the path of each resource with a version.
    private static final Set<String> VERSIONED = Set.of("org-units", "employments", "assignments", "people",
            "qualifications", "reporting-lines", "unit-roles", "ref-items", "ref-kinds", "hierarchies",
            "attribute-definitions", "change-orders");

    private final String serverUrl;
    private final String realm;
    private final String token;
    private final String acceptLanguage;

    Api(String realm, String token) {
        this(KEYCLOAK.getAuthServerUrl(), realm, token, null);
    }

    /** Calls another Keycloak than the shared one. */
    Api(String serverUrl, String realm, String token) {
        this(serverUrl, realm, token, null);
    }

    private Api(String serverUrl, String realm, String token, String acceptLanguage) {
        this.serverUrl = serverUrl;
        this.realm = realm;
        this.token = token;
        this.acceptLanguage = acceptLanguage;
    }

    Api withAcceptLanguage(String value) {
        return new Api(serverUrl, realm, token, value);
    }

    HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null);
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return send("POST", path, json);
    }

    /** Reads the number of a count read, for example {@code /people/count?q=sato}. */
    long count(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), path + " " + response.body());
        return json(response).get("count").asLong();
    }

    HttpResponse<String> put(String path, String json) throws Exception {
        return send("PUT", path, json);
    }

    HttpResponse<String> post(String path, String json, String header, String value) throws Exception {
        return send("POST", path, json, header, value);
    }

    HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    /**
     * Reads the version of a resource, for example {@code /employments/{id}}. It is null when the read fails.
     * An org unit is read with its history, which has every date.
     */
    Long version(String resource) throws Exception {
        String[] parts = resource.split("/", -1);
        HttpResponse<String> response = get(switch (parts[1]) {
            case "org-units" -> resource + "/history";
            case "ref-kinds" -> "/ref-kinds";
            default -> resource;
        });
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode read = json(response);
        if (read.isArray()) {
            for (JsonNode kind : read) {
                if (kind.get("code").asText().equals(parts[2]) && kind.has("version")) {
                    return kind.get("version").asLong();
                }
            }
            return null;
        }
        return read.get("version").asLong();
    }

    /**
     * Sends a write of a stored resource with the version that a read of the resource gives now, as a caller that
     * reads before it writes does. A body without {@code version} gets it. A write of a resource that cannot be
     * read is sent as it is.
     */
    HttpResponse<String> putWithVersion(String path, String json) throws Exception {
        return send("PUT", path, withVersion(path, json));
    }

    HttpResponse<String> postWithVersion(String path, String json) throws Exception {
        return send("POST", path, withVersion(path, json));
    }

    /** Sends a {@code DELETE} with the version of the resource in the query, as {@link #putWithVersion} does. */
    HttpResponse<String> deleteWithVersion(String path) throws Exception {
        Long version = versionOf(path, query(path));
        return delete(version == null ? path : path + (path.contains("?") ? "&" : "?") + "version=" + version);
    }

    private String withVersion(String path, String json) throws Exception {
        if (json == null) {
            return null;
        }
        JsonNode body;
        try {
            body = json.isBlank() ? BODIES.createObjectNode() : BODIES.readTree(json);
        } catch (JsonProcessingException e) {
            return json;
        }
        if (!(body instanceof ObjectNode object) || object.has("version")) {
            return json;
        }
        if (path.split("\\?", -1)[0].equals("/reorganizations")) {
            // Each step of a stored unit names the version of that unit.
            for (JsonNode step : object.path("steps")) {
                if (step instanceof ObjectNode change && change.has("id") && !change.has("unitRef")
                        && !change.has("version") && !"create".equals(change.path("op").asText())) {
                    Long unit = version("/org-units/" + change.get("id").asText());
                    if (unit != null) {
                        change.put("version", unit);
                    }
                }
            }
            return BODIES.writeValueAsString(object);
        }
        Long version = versionOf(path, object);
        if (version != null) {
            object.put("version", version);
        }
        return BODIES.writeValueAsString(object);
    }

    // The resource is the first two parts of the path, for example /employments/{id} of /employments/{id}/terms.
    // An ID of another system is named by the fields of the body, or of the query of a DELETE.
    private Long versionOf(String path, JsonNode fields) throws Exception {
        String[] parts = path.split("\\?", -1)[0].split("/", -1);
        if (parts.length == 2 && parts[1].equals("external-ids")) {
            HttpResponse<String> ids = get("/external-ids?resourceType=" + fields.path("resourceType").asText()
                    + "&resourceId=" + fields.path("resourceId").asText());
            if (ids.statusCode() == 200) {
                for (JsonNode id : json(ids)) {
                    if (id.get("source").asText().equals(fields.path("source").asText())) {
                        return id.get("version").asLong();
                    }
                }
            }
            return null;
        }
        if (parts.length < 3 || !VERSIONED.contains(parts[1])) {
            return null;
        }
        return version("/" + parts[1] + "/" + parts[2]);
    }

    private static JsonNode query(String path) {
        ObjectNode fields = BODIES.createObjectNode();
        int start = path.indexOf('?');
        if (start >= 0) {
            for (String pair : path.substring(start + 1).split("&", -1)) {
                String[] nameAndValue = pair.split("=", 2);
                fields.put(nameAndValue[0], nameAndValue.length > 1 ? nameAndValue[1] : "");
            }
        }
        return fields;
    }

    HttpResponse<String> postBytes(String path, String contentType, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create(serverUrl + "/admin/realms/" + realm + "/fuhito" + path))
                .POST(BodyPublishers.ofByteArray(body))
                .header("Content-Type", contentType)
                .header("Authorization", "Bearer " + token);
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Reads every org unit of a date in tree order, root by root. The list of every unit is not in tree order.
     * The query is for example {@code asOf=2026-04-01}, or an empty text.
     */
    JsonNode tree(String query) throws Exception {
        var all = MAPPER.createArrayNode();
        for (JsonNode root : json(get("/org-units?roots=true&max=1000&" + query))) {
            all.addAll((com.fasterxml.jackson.databind.node.ArrayNode) json(
                    get("/org-units/" + root.get("id").asText() + "/subtree?max=1000&" + query)));
        }
        return all;
    }

    static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    // The IDs that systems have for a record, as "source=externalId", in the order of the response.
    static List<String> externalIds(JsonNode record) {
        List<String> ids = new ArrayList<>();
        record.get("externalIds").forEach(id -> ids.add(id.get("source").asText() + "=" + id.get("externalId").asText()));
        return ids;
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return send(method, path, json, null, null);
    }

    private HttpResponse<String> send(String method, String path, String json, String header, String value)
            throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create(serverUrl + "/admin/realms/" + realm + "/fuhito" + path))
                .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (header != null) {
            request.header(header, value);
        }
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
