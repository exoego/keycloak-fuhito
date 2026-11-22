package net.exoego.keycloak.fuhito;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads every resource of a realm that has a derived value before a write, and again after it. Each read that changed
 * must have a new version and an entry in the change log after the first read. A read changes with no entry when a
 * write misses a propagated change.
 *
 * <p>The derived values come from their list in the provider jar. So a value that a later change adds to the list is
 * checked too.
 */
final class DerivedReads {

    /**
     * A derived value of the list in the provider jar.
     *
     * @param resourceType the type of the resource whose read has the value, as the change log names it
     * @param path the path of the resources of the type, for example {@code /org-units/}
     * @param field the field of the read
     */
    record Value(String resourceType, String path, String field) {
    }

    private record Resource(String type, String id, String path) {
    }

    private static final List<Value> VALUES = load();

    private final Api api;
    private final List<String> dates;
    private final long last;
    private final Map<String, JsonNode> before;

    private DerivedReads(Api api, List<String> dates) throws Exception {
        this.api = api;
        this.dates = dates;
        List<JsonNode> log = log(api);
        this.last = log.isEmpty() ? 0 : log.getLast().get("sequence").asLong();
        this.before = reads(resources(log));
    }

    /**
     * Reads the resources with a derived value before a write.
     *
     * @param api the API of the realm
     * @param asOf the dates to read each resource on. With none, each resource is read on today. A resource whose
     *         read takes no date, such as an assignment, is read once.
     * @return the reads, to compare after the write
     */
    static DerivedReads before(Api api, String... asOf) throws Exception {
        return new DerivedReads(api, List.of(asOf));
    }

    /** The derived values of the list in the provider jar. */
    static List<Value> values() {
        return VALUES;
    }

    /**
     * Reads the same resources again, and fails for each read that changed with no new version or with no entry in
     * the change log.
     */
    void assertEveryChangeIsLogged() throws Exception {
        List<String> missing = new ArrayList<>();
        Map<String, JsonNode> after = reads(resources(log(api)));
        for (Map.Entry<String, JsonNode> read : before.entrySet()) {
            JsonNode now = after.get(read.getKey());
            if (Objects.equals(read.getValue(), now)) {
                continue;
            }
            String[] parts = read.getKey().split(" ", 3);
            if (Objects.equals(read.getValue().get("version"), now.get("version"))) {
                missing.add("no new version: " + read.getKey());
            }
            if (Api.json(api.get("/change-log?after=" + last + "&resourceType=" + parts[0] + "&resourceId="
                    + parts[1])).isEmpty()) {
                missing.add("no entry: " + read.getKey());
            }
        }
        assertEquals(List.of(), missing);
    }

    // Every resource of a listed type that the change log has an entry of, by its type, ID, and path.
    private static Set<Resource> resources(List<JsonNode> log) {
        Set<Resource> resources = new LinkedHashSet<>();
        for (JsonNode entry : log) {
            for (Value value : VALUES) {
                if (value.resourceType().equals(entry.get("resourceType").asText())) {
                    String id = entry.get("resourceId").asText();
                    resources.add(new Resource(value.resourceType(), id, value.path() + id));
                }
            }
        }
        return resources;
    }

    private Map<String, JsonNode> reads(Set<Resource> resources) throws Exception {
        Map<String, JsonNode> reads = new LinkedHashMap<>();
        for (Resource resource : resources) {
            for (String query : dates.isEmpty() ? List.of("") : dates.stream().map(date -> "?asOf=" + date).toList()) {
                HttpResponse<String> response = api.get(resource.path() + query);
                if (response.statusCode() == 400 && Api.json(response).path("error").asText()
                        .equals("invalid.unknown_parameter")) {
                    query = "";
                    response = api.get(resource.path());
                }
                // A deleted resource answers 404. Any other error is a mistake of the test.
                assertTrue(response.statusCode() == 200 || response.statusCode() == 404,
                        resource.path() + query + " " + response.body());
                reads.put(resource.type() + " " + resource.id() + " " + query, Api.json(response));
            }
        }
        return reads;
    }

    private static List<JsonNode> log(Api api) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        for (int page = 1000; page == 1000; ) {
            String after = entries.isEmpty() ? "0" : entries.getLast().get("sequence").asText();
            JsonNode read = Api.json(api.get("/change-log?max=1000&after=" + after));
            read.forEach(entries::add);
            page = read.size();
        }
        return entries;
    }

    private static List<Value> load() {
        try (URLClassLoader jar = new URLClassLoader(
                new URL[] {new File(System.getProperty("provider.jar")).toURI().toURL()},
                DerivedReads.class.getClassLoader())) {
            Class<?> type = jar.loadClass("net.exoego.keycloak.fuhito.domain.DerivedValue");
            List<Value> values = new ArrayList<>();
            for (Object value : type.getEnumConstants()) {
                values.add(new Value(type.getMethod("resourceType").invoke(value).toString(),
                        ((String) type.getMethod("path", String.class).invoke(value, "")).substring("fuhito".length()),
                        (String) type.getMethod("field").invoke(value)));
            }
            return List.copyOf(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
