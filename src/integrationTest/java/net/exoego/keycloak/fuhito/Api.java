package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;

/**
 * Calls the admin REST API of the extension for one realm with one token.
 */
final class Api {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    HttpResponse<String> put(String path, String json) throws Exception {
        return send("PUT", path, json);
    }

    HttpResponse<String> put(String path, String json, String header, String value) throws Exception {
        return send("PUT", path, json, header, value);
    }

    HttpResponse<String> post(String path, String json, String header, String value) throws Exception {
        return send("POST", path, json, header, value);
    }

    HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    HttpResponse<String> delete(String path, String header, String value) throws Exception {
        return send("DELETE", path, null, header, value);
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
