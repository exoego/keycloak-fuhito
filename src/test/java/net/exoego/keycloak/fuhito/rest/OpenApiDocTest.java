package net.exoego.keycloak.fuhito.rest;

import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.QueryParam;
import net.exoego.keycloak.fuhito.service.ImportDocument;
import net.exoego.keycloak.fuhito.service.ImportReport;
import net.exoego.keycloak.fuhito.service.ProjectionReport;
import net.exoego.keycloak.fuhito.service.ReorganizationStep;
import net.exoego.keycloak.fuhito.service.SyncReport;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps docs/openapi.yaml in step with the code. The document is written by hand.
 */
class OpenApiDocTest {

    private static final Map<String, Object> DOC = load();

    private static final List<Class<? extends Annotation>> VERBS = List.of(GET.class, POST.class, PUT.class,
            DELETE.class);

    private static final Map<String, Class<? extends Record>> SCHEMAS = Map.ofEntries(
            Map.entry("Version", VersionRepresentation.class),
            Map.entry("Hierarchy", HierarchyRepresentation.class),
            Map.entry("Error", ErrorRepresentation.class),
            Map.entry("RefItem", RefItemRepresentation.class),
            Map.entry("OrgUnit", OrgUnitRepresentation.class),
            Map.entry("OrgUnitHistory", OrgUnitHistoryRepresentation.class),
            Map.entry("ParentPeriod", OrgUnitHistoryRepresentation.ParentPeriod.class),
            Map.entry("ParentChange", ParentChangeRepresentation.class),
            Map.entry("End", EndRepresentation.class),
            Map.entry("OrganizationLink", OrganizationLinkRepresentation.class),
            Map.entry("TimeSettings", TimeSettingsRepresentation.class),
            Map.entry("Member", MemberRepresentation.class),
            Map.entry("PathEntry", MemberRepresentation.PathEntry.class),
            Map.entry("Employment", EmploymentRepresentation.class),
            Map.entry("EmploymentTerms", EmploymentRepresentation.Terms.class),
            Map.entry("Assignment", AssignmentRepresentation.class),
            Map.entry("AssignmentDetails", AssignmentRepresentation.Details.class),
            Map.entry("Staff", StaffRepresentation.class),
            Map.entry("Reorganization", ReorganizationRepresentation.class),
            Map.entry("ReorganizationStep", ReorganizationStep.class),
            Map.entry("ReorganizationResult", ReorganizationRepresentation.Result.class),
            Map.entry("ChangeOrder", ChangeOrderRepresentation.class),
            Map.entry("ChangeLogEntry", ChangeLogRepresentation.class),
            Map.entry("Qualification", QualificationRepresentation.class),
            Map.entry("ReportingLine", ReportingLineRepresentation.class),
            Map.entry("Reporting", ReportingRepresentation.class),
            Map.entry("UserHistory", UserHistoryRepresentation.class),
            Map.entry("Mask", UsersResource.MaskRepresentation.class),
            Map.entry("ImportDocument", ImportDocument.class),
            Map.entry("ImportRefItemRow", ImportDocument.RefItemRow.class),
            Map.entry("ImportOrgUnitRow", ImportDocument.OrgUnitRow.class),
            Map.entry("ImportEmploymentRow", ImportDocument.EmploymentRow.class),
            Map.entry("ImportAssignmentRow", ImportDocument.AssignmentRow.class),
            Map.entry("ImportReport", ImportReportRepresentation.class),
            Map.entry("ImportCounts", ImportReport.Counts.class),
            Map.entry("ImportRowError", ImportReportRepresentation.RowError.class),
            Map.entry("Export", ExportRepresentation.class),
            Map.entry("ProjectionReport", ProjectionReport.class),
            Map.entry("SyncReport", SyncReportRepresentation.class),
            Map.entry("SyncUnmatched", SyncReport.Unmatched.class));

    @Test
    void theDocumentListsEveryOperationWithItsQueryParameters() {
        Map<String, Set<String>> inCode = new TreeMap<>();
        collect(FuhitoResource.class, "", inCode);

        Map<String, Set<String>> inDoc = new TreeMap<>();
        map(DOC.get("paths")).forEach((path, item) -> {
            List<Object> shared = list(map(item).get("parameters"));
            for (var verb : VERBS) {
                Object operation = map(item).get(verb.getSimpleName().toLowerCase(Locale.ROOT));
                if (operation != null) {
                    List<Object> parameters = new ArrayList<>(shared);
                    parameters.addAll(list(map(operation).get("parameters")));
                    inDoc.put(verb.getSimpleName() + " " + path, queryNames(parameters));
                }
            }
        });

        assertEquals(inCode, inDoc);
    }

    @Test
    void everySchemaHasTheFieldsOfItsRecord() {
        Map<String, Object> schemas = map(map(DOC.get("components")).get("schemas"));
        Map<String, Set<String>> inCode = new TreeMap<>();
        Map<String, Set<String>> inDoc = new TreeMap<>();
        SCHEMAS.forEach((name, type) -> {
            inCode.put(name, Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toCollection(TreeSet::new)));
            inDoc.put(name, new TreeSet<>(map(map(schemas.get(name)).get("properties")).keySet()));
        });

        assertEquals(inCode, inDoc);
    }

    @Test
    void everyObjectSchemaIsCheckedAgainstARecord() {
        Map<String, Object> schemas = map(map(DOC.get("components")).get("schemas"));
        Set<String> withProperties = schemas.entrySet().stream()
                .filter(schema -> map(schema.getValue()).containsKey("properties"))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(new TreeSet<>(SCHEMAS.keySet()), withProperties);
    }

    private static void collect(Class<?> resource, String prefix, Map<String, Set<String>> operations) {
        for (Method method : resource.getDeclaredMethods()) {
            var path = method.getAnnotation(jakarta.ws.rs.Path.class);
            String full = path == null ? prefix : prefix + "/" + path.value();
            var verb = VERBS.stream().filter(method::isAnnotationPresent).findFirst();
            if (verb.isPresent()) {
                Set<String> query = Arrays.stream(method.getParameters())
                        .map(parameter -> parameter.getAnnotation(QueryParam.class))
                        .filter(annotation -> annotation != null)
                        .map(QueryParam::value)
                        .collect(Collectors.toCollection(TreeSet::new));
                // Every write can name its change order. The request context reads it, not the method.
                if (verb.get() != GET.class) {
                    query.add("changeOrder");
                }
                operations.put(verb.get().getSimpleName() + " " + (full.isEmpty() ? "/" : full), query);
            } else if (path != null) {
                collect(method.getReturnType(), full, operations);
            }
        }
    }

    private static Set<String> queryNames(List<Object> parameters) {
        Map<String, Object> shared = map(map(DOC.get("components")).get("parameters"));
        return parameters.stream()
                .map(OpenApiDocTest::map)
                .map(parameter -> parameter.containsKey("$ref")
                        ? map(shared.get(parameter.get("$ref").toString().replace("#/components/parameters/", "")))
                        : parameter)
                .filter(parameter -> "query".equals(parameter.get("in")))
                .map(parameter -> parameter.get("name").toString())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Object> load() {
        try (Reader reader = Files.newBufferedReader(Path.of("docs/openapi.yaml"), StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // YAML has no types, so the shape is only known when the test reads it.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }
}
