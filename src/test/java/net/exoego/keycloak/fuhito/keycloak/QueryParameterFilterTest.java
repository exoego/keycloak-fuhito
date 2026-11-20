package net.exoego.keycloak.fuhito.keycloak;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import net.exoego.keycloak.fuhito.rest.FuhitoResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryParameterFilterTest {

    static class Endpoints {

        @GET
        public String read(@PathParam("id") String id, @QueryParam("asOf") String asOf, @QueryParam("max") Integer max) {
            return id + asOf + max;
        }

        @POST
        public String write(@QueryParam("dryRun") boolean dryRun, String body) {
            return dryRun + body;
        }

        @DELETE
        public String remove(@QueryParam("version") Long version) {
            return String.valueOf(version);
        }
    }

    private static Method endpoint(String name) throws Exception {
        for (Method method : Endpoints.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test
    void aMethodTakesItsQueryParametersAndAWriteAlsoTheChangeOrder() throws Exception {
        assertEquals(Set.of("asOf", "max"), QueryParameterFilter.taken(endpoint("read")));
        assertEquals(Set.of("dryRun", "changeOrder"), QueryParameterFilter.taken(endpoint("write")));
        assertEquals(Set.of("version", "changeOrder"), QueryParameterFilter.taken(endpoint("remove")));
    }

    @Test
    void namesTheFirstParameterThatTheMethodCannotTake() throws Exception {
        Method read = endpoint("read");

        assertEquals(Optional.empty(), QueryParameterFilter.problem(read, Map.of()));
        assertEquals(Optional.empty(), QueryParameterFilter.problem(read, Map.of("asOf", List.of("2026-04-01"),
                "max", List.of(""))));
        assertEquals(Optional.of(Map.entry(QueryParameterFilter.UNKNOWN, "asof")),
                QueryParameterFilter.problem(read, Map.of("asof", List.of("2026-04-01"))));
        assertEquals(Optional.of(Map.entry(QueryParameterFilter.TWICE, "asOf")),
                QueryParameterFilter.problem(read, Map.of("asOf", List.of("2026-04-01", "2026-04-01"))));
        assertEquals(Optional.of(Map.entry(QueryParameterFilter.UNKNOWN, "changeOrder")),
                QueryParameterFilter.problem(read, Map.of("changeOrder", List.of("x"))));
        assertEquals(Optional.of(Map.entry(QueryParameterFilter.TWICE, "asOf")),
                QueryParameterFilter.problem(read, Map.of("max", List.of("1", "2"), "asOf", List.of("a", "b"))));
    }

    // A client that follows the document never gets the error.
    @Test
    void theOpenApiDocumentListsEveryQueryParameterThatEachEndpointTakes() throws Exception {
        JsonNode paths = new ObjectMapper(new YAMLFactory())
                .readTree(new File(System.getProperty("openapi.document"))).get("paths");
        Map<String, Method> endpoints = endpoints();
        Set<String> documented = new HashSet<>();

        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                String key = operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey();
                documented.add(key);
                Method method = endpoints.get(key);
                assertTrue(method != null, key);
                Set<String> query = new HashSet<>();
                for (JsonNode parameter : operation.getValue().path("parameters")) {
                    if (parameter.has("$ref")) {
                        String ref = parameter.get("$ref").asText();
                        query.add(ref.substring(ref.lastIndexOf('/') + 1));
                    } else if (parameter.get("in").asText().equals("query")) {
                        query.add(parameter.get("name").asText());
                    }
                }
                assertEquals(QueryParameterFilter.taken(method), query, key);
                assertTrue(operation.getValue().get("responses").has("400"), key);
            }
        }
        assertEquals(endpoints.keySet(), documented);
    }

    // Each resource method of the extension by its HTTP method and path, as the OpenAPI document names them.
    private static Map<String, Method> endpoints() {
        Map<String, Method> endpoints = new TreeMap<>();
        for (Method locator : FuhitoResource.class.getDeclaredMethods()) {
            Path path = locator.getAnnotation(Path.class);
            if (httpMethod(locator) != null) {
                endpoints.put(httpMethod(locator) + " /", locator);
            } else if (path != null) {
                for (Method method : locator.getReturnType().getDeclaredMethods()) {
                    String verb = httpMethod(method);
                    if (verb != null) {
                        Path below = method.getAnnotation(Path.class);
                        endpoints.put(verb + " /" + path.value() + (below == null ? "" : "/" + below.value()), method);
                    }
                }
            }
        }
        return endpoints;
    }

    private static String httpMethod(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            HttpMethod verb = annotation.annotationType().getAnnotation(HttpMethod.class);
            if (verb != null) {
                return verb.value();
            }
        }
        return null;
    }
}
