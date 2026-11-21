package net.exoego.keycloak.fuhito.keycloak;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
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

        @GET
        public String list(@QueryParam("first") Integer first, @QueryParam("after") Long after,
                @QueryParam("count") int count, @QueryParam("size") long size, @QueryParam("roots") boolean roots) {
            return first + " " + after + " " + count + " " + size + " " + roots;
        }

        @GET
        public String search(@QueryParam("q") String q, @QueryParam("lateral") Boolean lateral,
                @QueryParam("on") LocalDate on, @QueryParam("day") DayOfWeek day) {
            return q + lateral + on + day;
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
        assertEquals(Map.of("asOf", String.class, "max", Integer.class), QueryParameterFilter.taken(endpoint("read")));
        assertEquals(Map.of("dryRun", boolean.class, "changeOrder", String.class),
                QueryParameterFilter.taken(endpoint("write")));
        assertEquals(Map.of("version", Long.class, "changeOrder", String.class),
                QueryParameterFilter.taken(endpoint("remove")));
    }

    @Test
    void namesTheFirstParameterThatTheMethodCannotTake() throws Exception {
        Method read = endpoint("read");

        assertEquals(Optional.empty(), QueryParameterFilter.problem(read, Map.of()));
        assertEquals(Optional.empty(), QueryParameterFilter.problem(read, Map.of("asOf", List.of("2026-04-01"),
                "max", List.of("1000"))));
        assertEquals(problem(QueryParameterFilter.BLANK, "asOf"),
                QueryParameterFilter.problem(read, Map.of("asOf", List.of(""))));
        assertEquals(problem(QueryParameterFilter.UNKNOWN, "asof"),
                QueryParameterFilter.problem(read, Map.of("asof", List.of("2026-04-01"))));
        assertEquals(problem(QueryParameterFilter.TWICE, "asOf"),
                QueryParameterFilter.problem(read, Map.of("asOf", List.of("2026-04-01", "2026-04-01"))));
        assertEquals(problem(QueryParameterFilter.UNKNOWN, "changeOrder"),
                QueryParameterFilter.problem(read, Map.of("changeOrder", List.of("x"))));
        assertEquals(problem(QueryParameterFilter.TWICE, "asOf"),
                QueryParameterFilter.problem(read, Map.of("max", List.of("1", "2"), "asOf", List.of("a", "b"))));
        assertEquals(problem(QueryParameterFilter.TWICE, "max"),
                QueryParameterFilter.problem(read, Map.of("max", List.of("abc", "abc"))));
        assertEquals(problem(QueryParameterFilter.UNKNOWN, "asof"),
                QueryParameterFilter.problem(read, Map.of("max", List.of("abc"), "asof", List.of("x"))));
    }

    private static Optional<QueryParameterFilter.Problem> problem(String code, String field) {
        return Optional.of(new QueryParameterFilter.Problem(code, Map.of("field", field)));
    }

    private static Optional<QueryParameterFilter.Problem> notWhole(String field) {
        return Optional.of(new QueryParameterFilter.Problem(QueryParameterFilter.TYPE,
                Map.of("field", field, "type", "integer")));
    }

    private static Optional<QueryParameterFilter.Problem> notBoolean(String field) {
        return Optional.of(new QueryParameterFilter.Problem(QueryParameterFilter.TYPE,
                Map.of("field", field, "type", "boolean")));
    }

    private static Optional<QueryParameterFilter.Problem> outOfRange(String field, long min, long max) {
        return Optional.of(new QueryParameterFilter.Problem(QueryParameterFilter.OUT_OF_RANGE,
                Map.of("field", field, "min", Long.toString(min), "max", Long.toString(max))));
    }

    @Test
    void aWholeNumberHasTheFormOfJson() throws Exception {
        Method read = endpoint("read");

        for (String bad : List.of("", "abc", "1.5", "1e2", "1.0", "+5", " 5", "5 ", "\uFF15", "\u0665", "007", "-",
                "0x10", "1_000", "--1")) {
            assertEquals(notWhole("max"), QueryParameterFilter.problem(read, Map.of("max", List.of(bad))), bad);
        }
        assertEquals(notWhole("first"), QueryParameterFilter.problem(endpoint("list"), Map.of("first", List.of(""))));
        assertEquals(notWhole("version"),
                QueryParameterFilter.problem(endpoint("remove"), Map.of("version", List.of("2e0"))));
        assertEquals(notWhole("count"), QueryParameterFilter.problem(endpoint("list"), Map.of("count", List.of("x"))));
        assertEquals(notBoolean("roots"),
                QueryParameterFilter.problem(endpoint("list"), Map.of("roots", List.of("1"))));
    }

    @Test
    void aBooleanIsTrueOrFalseAsInJson() throws Exception {
        Method write = endpoint("write");
        Method search = endpoint("search");

        for (String bad : List.of("", "abc", "True", "TRUE", "False", "FALSE", "1", "0", "yes", "no", " true",
                "false ", "\"true\"", "t", "on")) {
            assertEquals(notBoolean("dryRun"), QueryParameterFilter.problem(write, Map.of("dryRun", List.of(bad))),
                    bad);
            assertEquals(notBoolean("lateral"), QueryParameterFilter.problem(search, Map.of("lateral", List.of(bad))),
                    bad);
        }
        for (String good : List.of("true", "false")) {
            assertEquals(Optional.empty(), QueryParameterFilter.problem(write, Map.of("dryRun", List.of(good))), good);
            assertEquals(Optional.empty(), QueryParameterFilter.problem(search, Map.of("lateral", List.of(good))),
                    good);
        }
    }

    // A text of a body fails as blank when Checks.optionalText fails.
    @Test
    void aTextIsNotEmptyOrOnlySpacesAsInABody() throws Exception {
        Method search = endpoint("search");

        for (String text : List.of("", " ", "   ", "\t", "\n", "\r\n", "\u3000", "\u2003", " \t\u3000", "\u00A0",
                "\u2007", "\u202F", "x", " x ", "2026-04-01", "MONDAY")) {
            boolean blankInABody = blankInABody(text);
            for (String name : List.of("q", "on", "day")) {
                assertEquals(blankInABody ? problem(QueryParameterFilter.BLANK, name) : Optional.empty(),
                        QueryParameterFilter.problem(search, Map.of(name, List.of(text))), name + " " + text);
            }
            assertEquals(blankInABody ? problem(QueryParameterFilter.BLANK, "changeOrder") : Optional.empty(),
                    QueryParameterFilter.problem(endpoint("write"), Map.of("changeOrder", List.of(text))), text);
        }
        assertEquals(problem(QueryParameterFilter.BLANK, "q"),
                QueryParameterFilter.problem(search, Map.of("q", List.of(" "))));
        assertEquals(Optional.empty(), QueryParameterFilter.problem(search, Map.of("q", List.of("\u00A0"))));
    }

    private static boolean blankInABody(String text) {
        try {
            Checks.optionalText("field", text);
            return false;
        } catch (FuhitoException e) {
            return e.code().equals("invalid.blank");
        }
    }

    @Test
    void aWholeNumberIsInsideTheLimitsOfTheDocument() throws Exception {
        Method read = endpoint("read");
        Method list = endpoint("list");
        Method remove = endpoint("remove");

        assertEquals(outOfRange("max", 0, 1000), QueryParameterFilter.problem(read, Map.of("max", List.of("1001"))));
        assertEquals(outOfRange("max", 0, 1000), QueryParameterFilter.problem(read, Map.of("max", List.of("-1"))));
        assertEquals(outOfRange("max", 0, 1000),
                QueryParameterFilter.problem(read, Map.of("max", List.of("99999999999999999999"))));
        assertEquals(outOfRange("first", 0, Integer.MAX_VALUE),
                QueryParameterFilter.problem(list, Map.of("first", List.of("-1"))));
        assertEquals(outOfRange("first", 0, Integer.MAX_VALUE),
                QueryParameterFilter.problem(list, Map.of("first", List.of("2147483648"))));
        assertEquals(outOfRange("after", 0, Long.MAX_VALUE),
                QueryParameterFilter.problem(list, Map.of("after", List.of("-1"))));
        assertEquals(outOfRange("after", 0, Long.MAX_VALUE),
                QueryParameterFilter.problem(list, Map.of("after", List.of("9223372036854775808"))));
        assertEquals(outOfRange("count", Integer.MIN_VALUE, Integer.MAX_VALUE),
                QueryParameterFilter.problem(list, Map.of("count", List.of("-2147483649"))));
        assertEquals(outOfRange("version", Long.MIN_VALUE, Long.MAX_VALUE),
                QueryParameterFilter.problem(remove, Map.of("version", List.of("9223372036854775808"))));

        for (String good : List.of("0", "-0", "1000")) {
            assertEquals(Optional.empty(), QueryParameterFilter.problem(read, Map.of("max", List.of(good))), good);
        }
        assertEquals(Optional.empty(), QueryParameterFilter.problem(list, Map.of("first", List.of("2147483647"),
                "after", List.of("9223372036854775807"), "count", List.of("-2147483648"),
                "size", List.of("-9223372036854775808"))));
        assertEquals(Optional.empty(), QueryParameterFilter.problem(list, Map.of("first", List.of("0"),
                "after", List.of("0"))));
        for (String good : List.of("-9223372036854775808", "-1", "9223372036854775807")) {
            assertEquals(Optional.empty(), QueryParameterFilter.problem(remove, Map.of("version", List.of(good))),
                    good);
        }
    }

    // A client that follows the document never gets the error.
    @Test
    void theOpenApiDocumentListsEveryQueryParameterThatEachEndpointTakes() throws Exception {
        Map<String, Method> endpoints = endpoints();
        Map<String, JsonNode> operations = operations();

        for (Map.Entry<String, JsonNode> operation : operations.entrySet()) {
            String key = operation.getKey();
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
            assertEquals(QueryParameterFilter.taken(method).keySet(), query, key);
            assertTrue(operation.getValue().get("responses").has("400"), key);
        }
        assertEquals(endpoints.keySet(), operations.keySet());
    }

    // The filter checks the limits that the document gives. A format with no minimum or maximum gives the limits of
    // its type.
    @Test
    void theOpenApiDocumentGivesTheLimitsOfEachWholeNumberThatTheFilterChecks() throws Exception {
        Map<String, Method> endpoints = endpoints();
        Set<String> numbers = new HashSet<>();

        for (Map.Entry<String, JsonNode> operation : operations().entrySet()) {
            Map<String, Class<?>> taken = QueryParameterFilter.taken(endpoints.get(operation.getKey()));
            for (JsonNode parameter : operation.getValue().path("parameters")) {
                if (!parameter.path("in").asText().equals("query")) {
                    continue;
                }
                String name = parameter.get("name").asText();
                String where = operation.getKey() + " " + name;
                JsonNode schema = parameter.get("schema");
                Optional<QueryParameterFilter.Limits> limits = QueryParameterFilter.limits(taken.get(name), name);
                assertEquals(schema.path("type").asText().equals("integer"), limits.isPresent(), where);
                if (limits.isPresent()) {
                    boolean int64 = schema.get("format").asText().equals("int64");
                    long min = schema.has("minimum") ? schema.get("minimum").asLong()
                            : int64 ? Long.MIN_VALUE : Integer.MIN_VALUE;
                    long max = schema.has("maximum") ? schema.get("maximum").asLong()
                            : int64 ? Long.MAX_VALUE : Integer.MAX_VALUE;
                    assertEquals(new QueryParameterFilter.Limits(min, max), limits.get(), where);
                    numbers.add(name);
                }
            }
        }
        assertEquals(Set.of("first", "max", "after", "version"), numbers);
    }

    // The filter checks each value by the type that the document gives.
    @Test
    void theOpenApiDocumentGivesTheTypeThatTheFilterChecks() throws Exception {
        Map<String, Method> endpoints = endpoints();
        JsonNode components = document().get("components");
        Set<String> booleans = new HashSet<>();

        for (Map.Entry<String, JsonNode> operation : operations().entrySet()) {
            Map<String, Class<?>> taken = QueryParameterFilter.taken(endpoints.get(operation.getKey()));
            for (JsonNode parameter : operation.getValue().path("parameters")) {
                if (parameter.has("$ref")) {
                    parameter = components.get("parameters").get(name(parameter));
                }
                if (!parameter.get("in").asText().equals("query")) {
                    continue;
                }
                String name = parameter.get("name").asText();
                JsonNode schema = parameter.get("schema");
                if (schema.has("$ref")) {
                    schema = components.get("schemas").get(name(schema));
                }
                assertEquals(schema.get("type").asText(), QueryParameterFilter.jsonType(taken.get(name)),
                        operation.getKey() + " " + name);
                if (schema.get("type").asText().equals("boolean")) {
                    booleans.add(name);
                }
            }
        }
        assertEquals(Set.of("dryRun", "roots", "nearest", "includePath", "includeInactive", "includeDescendants",
                "clearKeycloakName", "all", "replace", "lateral", "seconded", "rollup", "legalEntity", "clearValues"),
                booleans);
    }

    private static String name(JsonNode ref) {
        String path = ref.get("$ref").asText();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static JsonNode document() throws Exception {
        return new ObjectMapper(new YAMLFactory()).readTree(new File(System.getProperty("openapi.document")));
    }

    // Each operation of the OpenAPI document by its HTTP method and path.
    private static Map<String, JsonNode> operations() throws Exception {
        JsonNode paths = document().get("paths");
        Map<String, JsonNode> operations = new TreeMap<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                operations.put(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(), operation.getValue());
            }
        }
        return operations;
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
