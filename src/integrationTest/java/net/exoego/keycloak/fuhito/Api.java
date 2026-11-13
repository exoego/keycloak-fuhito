package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;

/**
 * Calls the admin REST API of the extension for one realm with one token.
 */
final class Api {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String realm;
    private final String token;
    private final String acceptLanguage;

    Api(String realm, String token) {
        this(realm, token, null);
    }

    private Api(String realm, String token, String acceptLanguage) {
        this.realm = realm;
        this.token = token;
        this.acceptLanguage = acceptLanguage;
    }

    Api withAcceptLanguage(String value) {
        return new Api(realm, token, value);
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

    HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    HttpResponse<String> postBytes(String path, String contentType, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm + "/fuhito" + path))
                .POST(BodyPublishers.ofByteArray(body))
                .header("Content-Type", contentType)
                .header("Authorization", "Bearer " + token);
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm + "/fuhito" + path))
                .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (json != null) {
            request.header("Content-Type", "application/json");
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
