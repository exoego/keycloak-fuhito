package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefItemRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Loads many rows in one request.
 * The caller runs it in one transaction and rolls back when the report has errors or the import is a dry run.
 */
public class ImportService {

    /**
     * The source of rows that name none.
     */
    public static final String DEFAULT_SOURCE = "import";

    private static final String REF_ITEMS = "refItems";
    private static final String ORG_UNITS = "orgUnits";
    private static final String EMPLOYMENTS = "employments";
    private static final String ASSIGNMENTS = "assignments";

    /**
     * The services and stores that an import of people needs.
     *
     * @param employmentService the operations on employments of the realm
     * @param employments the storage of employments of the realm
     * @param assignmentService the operations on assignments of the realm
     * @param assignments the storage of assignments of the realm
     * @param users the users of the realm
     */
    public record People(EmploymentService employmentService, EmploymentStore employments,
            AssignmentService assignmentService, AssignmentStore assignments, UserDirectory users) {
    }

    private final RefItemService refItemService;
    private final RefItemStore refItems;
    private final OrgUnitService orgUnitService;
    private final OrgUnitStore orgUnits;
    private final People people;

    /**
     * Creates the service.
     *
     * @param refItemService the operations on reference items of the realm
     * @param refItems the storage of reference items of the realm
     * @param orgUnitService the operations on org units of the realm
     * @param orgUnits the storage of org units of the realm
     * @param people what an import of employments and assignments needs
     */
    public ImportService(RefItemService refItemService, RefItemStore refItems, OrgUnitService orgUnitService,
            OrgUnitStore orgUnits, People people) {
        this.refItemService = refItemService;
        this.refItems = refItems;
        this.orgUnitService = orgUnitService;
        this.orgUnits = orgUnits;
        this.people = people;
    }

    /**
     * Applies every row of the document. A bad row does not stop the others, so that the report names all of them.
     *
     * @param document the document
     * @return what each list did and which rows failed
     * @throws FuhitoException when the document itself is bad, for example with no effective date
     */
    public ImportReport apply(ImportDocument document) {
        Checks.required("body", document);
        Checks.date("effectiveDate", document.effectiveDate());
        String source = Checks.optionalCode("source", document.source());
        ImportReport report = new ImportReport();
        if (document.refItems() != null) {
            importRefItems(document.refItems(), report);
        }
        String defaultSource = source == null ? DEFAULT_SOURCE : source;
        if (document.orgUnits() != null) {
            new OrgUnitImport(document.effectiveDate(), defaultSource, document.orgUnits(), report).run();
        }
        if (document.employments() != null) {
            Set<String> seen = new HashSet<>();
            importRows(EMPLOYMENTS, document.employments(), report,
                    row -> importEmployment(row, document.effectiveDate(), defaultSource, report, seen));
        }
        if (document.assignments() != null) {
            // A row that ends or gives up the primary flag is applied before a row that takes the flag.
            List<AssignmentRow> rows = document.assignments();
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparing(i -> rows.get(i) != null && rows.get(i).validTo() == null
                    && Boolean.TRUE.equals(rows.get(i).primary())));
            report.startList(ASSIGNMENTS);
            Set<String> seen = new HashSet<>();
            for (int i : order) {
                try {
                    AssignmentRow row = Checks.required("row", rows.get(i));
                    requireFirst(seen, row.source(), row.externalId(), defaultSource);
                    report.count(ASSIGNMENTS, importAssignment(row, document.effectiveDate(), defaultSource, report));
                } catch (FuhitoException e) {
                    report.error(ASSIGNMENTS, i, e);
                }
            }
        }
        return report;
    }

    private <R> void importRows(String list, List<R> rows, ImportReport report, Function<R, Outcome> importer) {
        report.startList(list);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(list, importer.apply(Checks.required("row", rows.get(i))));
            } catch (FuhitoException e) {
                report.error(list, i, e);
            }
        }
    }

    private static void requireFirst(Set<String> seen, String source, String externalId, String defaultSource) {
        Checks.required("externalId", externalId);
        if (!seen.add((source == null ? defaultSource : source) + "\\u0000" + externalId)) {
            throw FuhitoException.invalid("import.duplicate_row", Map.of("externalId", externalId));
        }
    }

    private Outcome importEmployment(EmploymentRow row, String effectiveDate, String defaultSource,
            ImportReport report, Set<String> seen) {
        requireFirst(seen, row.source(), row.externalId(), defaultSource);
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        String userId = userId(row);
        Optional<Employment> stored = people.employments().findByExternalId(source, row.externalId());
        if (stored.isEmpty()) {
            EmploymentView created = people.employmentService().start(new NewEmployment(userId,
                    row.validFrom() == null ? effectiveDate : row.validFrom(), row.validTo(), row.staffNumber(),
                    row.employmentType(), row.grade(), row.status(), row.workPercent(), row.attributes(),
                    row.externalId(), source));
            String id = created.employment().id();
            written(report, id, true, "", created);
            if (row.validTo() != null && row.endReason() != null) {
                written(report, id, false, "/end",
                        people.employmentService().end(id, row.validTo(), row.endReason()));
            }
            return Outcome.CREATED;
        }
        Employment employment = stored.get();
        String id = employment.id();
        if (!employment.userId().equals(userId)) {
            throw FuhitoException.conflict("import.user_mismatch", Map.of("externalId", row.externalId()));
        }
        EmploymentView before = people.employmentService().get(id);
        boolean changed = false;
        // An employment that ended before the effective date has no terms to compare on that date.
        boolean endsBefore = employment.period().to() != null
                && !employment.period().to().isAfter(Checks.date("effectiveDate", effectiveDate));
        if (!endsBefore) {
            EmploymentView.TermsView terms = before.terms().stream()
                    .filter(t -> t.terms().period().contains(Checks.date("effectiveDate", effectiveDate)))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("employment.not_valid_on",
                            Map.of("id", id, "date", effectiveDate)));
            if (!sameTerms(row, terms)) {
                written(report, id, false, "/terms", people.employmentService().changeTerms(id, new TermsChange(
                        effectiveDate, row.employmentType(), row.grade(), row.status(), row.workPercent(),
                        row.attributes())));
                changed = true;
            }
        }
        String storedEnd = Objects.toString(employment.period().to(), null);
        boolean sameEnd = Objects.equals(row.validTo(), storedEnd)
                && (row.endReason() == null || row.endReason().equals(before.endReason()));
        if (row.validTo() != null && !sameEnd) {
            written(report, id, false, "/end", people.employmentService().end(id, row.validTo(), row.endReason()));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private String userId(EmploymentRow row) {
        if (row.userId() != null) {
            return row.userId();
        }
        String username = Checks.required("username", row.username());
        return people.users().findIdByUsername(username)
                .orElseThrow(() -> FuhitoException.invalid("import.username_unknown", Map.of("username", username)));
    }

    private static boolean sameTerms(EmploymentRow row, EmploymentView.TermsView terms) {
        var stored = terms.terms();
        String status = row.status() == null ? "ACTIVE" : row.status();
        return Objects.equals(row.employmentType(), terms.employmentType())
                && Objects.equals(row.grade(), terms.grade())
                && status.equals(stored.status().name())
                && sameNumber(row.workPercent(), stored.workPercent())
                && (row.attributes() == null ? Map.of() : row.attributes()).equals(stored.attributes());
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void written(ImportReport report, String employmentId, boolean created, String suffix,
            EmploymentView state) {
        report.write(new Write("FUHITO_EMPLOYMENT", employmentId, created,
                "fuhito/employments/" + employmentId + suffix, state));
    }

    private Outcome importAssignment(AssignmentRow row, String effectiveDate, String defaultSource,
            ImportReport report) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        String employmentExternalId = Checks.required("employmentExternalId", row.employmentExternalId());
        String orgUnitExternalId = Checks.required("orgUnitExternalId", row.orgUnitExternalId());
        String employmentId = people.employments().findByExternalId(source, employmentExternalId)
                .map(Employment::id)
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", employmentExternalId)));
        String orgUnitId = orgUnits.findUnitByExternalId(source, orgUnitExternalId)
                .map(OrgUnit::id)
                .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                        Map.of("orgUnitExternalId", orgUnitExternalId)));
        AssignmentValues values = new AssignmentValues(employmentId, orgUnitId,
                row.validFrom() == null ? effectiveDate : row.validFrom(), row.validTo(), row.jobTitle(),
                row.occupation(), row.kind(), row.head(), row.primary(), false, row.allocationPercent(),
                row.displayOrder(), row.attributes(), row.externalId(), source);

        Optional<String> stored = people.assignments().findAssignmentIdByExternalId(source, row.externalId());
        if (stored.isEmpty()) {
            AssignmentView created = people.assignmentService().add(values);
            written(report, created.assignmentId(), true, "", created);
            return Outcome.CREATED;
        }
        String id = stored.get();
        AssignmentView before = people.assignmentService().get(id);
        Assignment first = before.rows().getFirst().row();
        if (!first.employmentId().equals(employmentId) || !first.orgUnitId().equals(orgUnitId)) {
            throw FuhitoException.conflict("import.assignment_mismatch", Map.of("externalId", row.externalId()));
        }
        boolean changed = false;
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        boolean endsBefore = !before.period().endsAfter(date);
        if (!endsBefore) {
            AssignmentView.Row current = before.rows().stream()
                    .filter(r -> r.row().period().contains(date))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("assignment.not_valid_on",
                            Map.of("id", id, "date", effectiveDate)));
            if (!sameDetails(row, current)) {
                written(report, id, false, "/details", people.assignmentService().changeDetails(id,
                        new AssignmentValues(null, null, effectiveDate, null, row.jobTitle(), row.occupation(),
                                row.kind(), row.head(), row.primary(), false, row.allocationPercent(),
                                row.displayOrder(), row.attributes(), null, null)));
                changed = true;
            }
        }
        String storedEnd = Objects.toString(before.period().to(), null);
        if (row.validTo() != null && !row.validTo().equals(storedEnd)) {
            written(report, id, false, "/end", people.assignmentService().end(id, row.validTo()));
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private static boolean sameDetails(AssignmentRow row, AssignmentView.Row current) {
        Assignment stored = current.row();
        return Objects.equals(row.jobTitle(), current.jobTitle())
                && Objects.equals(row.occupation(), current.occupation())
                && Objects.equals(row.kind(), current.kind())
                && Boolean.TRUE.equals(row.head()) == stored.head()
                && Boolean.TRUE.equals(row.primary()) == stored.primary()
                && sameNumber(row.allocationPercent(), stored.allocationPercent())
                && (row.displayOrder() == null ? 0 : row.displayOrder()) == stored.displayOrder()
                && (row.attributes() == null ? Map.of() : row.attributes()).equals(stored.attributes());
    }

    private void written(ImportReport report, String assignmentId, boolean created, String suffix,
            AssignmentView state) {
        report.write(new Write("FUHITO_ASSIGNMENT", assignmentId, created,
                "fuhito/assignments/" + assignmentId + suffix, state));
    }

    private void importRefItems(List<RefItemRow> rows, ImportReport report) {
        report.startList(REF_ITEMS);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(REF_ITEMS, importRefItem(Checks.required("row", rows.get(i)), report));
            } catch (FuhitoException e) {
                report.error(REF_ITEMS, i, e);
            }
        }
    }

    private Outcome importRefItem(RefItemRow row, ImportReport report) {
        RefKind kind = Checks.optionalEnum("kind", RefKind.class, Checks.required("kind", row.kind()), null);
        String code = Checks.code("code", row.code());
        Optional<RefItem> stored = refItems.findByCode(kind, code);
        if (stored.isEmpty()) {
            RefItem created = refItemService.create(kind, code, row.name(), row.names(), row.displayOrder(),
                    row.attributes());
            RefItem item = Boolean.FALSE.equals(row.active())
                    ? refItemService.update(created.id(), row.name(), row.names(), row.displayOrder(),
                            row.attributes(), false)
                    : created;
            report.write(new Write("FUHITO_REF_ITEM", item.id(), true, "fuhito/ref-items/" + item.id(), item));
            return Outcome.CREATED;
        }
        RefItem before = stored.get();
        RefItem wanted = new RefItem(before.id(), kind, code, Checks.name("name", row.name()),
                Checks.localizedNames("names", row.names()), row.displayOrder() == null ? 0 : row.displayOrder(),
                row.attributes() == null ? Map.of() : row.attributes(), row.active() == null || row.active());
        if (wanted.equals(before)) {
            return Outcome.UNCHANGED;
        }
        RefItem item = refItemService.update(before.id(), row.name(), row.names(), row.displayOrder(),
                row.attributes(), row.active());
        report.write(new Write("FUHITO_REF_ITEM", item.id(), false, "fuhito/ref-items/" + item.id(), item));
        return Outcome.CHANGED;
    }

    /**
     * The org units of one document. A parent is applied before its children, wherever it is in the list.
     */
    private final class OrgUnitImport {

        private final String effectiveDate;
        private final String defaultSource;
        private final List<OrgUnitRow> rows;
        private final ImportReport report;
        private final Map<String, Integer> rowByKey = new HashMap<>();
        private final Map<Integer, String> unitIdByRow = new HashMap<>();
        private final Set<Integer> done = new HashSet<>();
        private final Set<Integer> failed = new HashSet<>();
        private final Set<Integer> running = new HashSet<>();

        OrgUnitImport(String effectiveDate, String defaultSource, List<OrgUnitRow> rows, ImportReport report) {
            this.effectiveDate = effectiveDate;
            this.defaultSource = defaultSource;
            this.rows = rows;
            this.report = report;
        }

        void run() {
            report.startList(ORG_UNITS);
            for (int i = 0; i < rows.size(); i++) {
                OrgUnitRow row = rows.get(i);
                if (row == null || row.externalId() == null) {
                    fail(i, FuhitoException.invalid("invalid.required", Map.of("field", "externalId")));
                } else if (rowByKey.putIfAbsent(key(row), i) != null) {
                    fail(i, FuhitoException.invalid("import.duplicate_row", Map.of("externalId", row.externalId())));
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                apply(i);
            }
        }

        private String sourceOf(OrgUnitRow row) {
            return row.source() == null ? defaultSource : row.source();
        }

        private String key(OrgUnitRow row) {
            return sourceOf(row) + "\\u0000" + row.externalId();
        }

        private void fail(int index, FuhitoException error) {
            if (failed.add(index)) {
                done.add(index);
                report.error(ORG_UNITS, index, error);
            }
        }

        private void apply(int index) {
            if (done.contains(index)) {
                return;
            }
            if (!running.add(index)) {
                throw FuhitoException.invalid("import.parent_cycle",
                        Map.of("externalId", rows.get(index).externalId()));
            }
            try {
                OrgUnitRow row = rows.get(index);
                String source = Checks.code("source", sourceOf(row));
                String parentId = parentId(row, source);
                report.count(ORG_UNITS, applyRow(index, row, source, parentId));
            } catch (FuhitoException e) {
                fail(index, e);
            } finally {
                running.remove(index);
                done.add(index);
            }
        }

        // Applies the parent row first when the parent is in the same document.
        private String parentId(OrgUnitRow row, String source) {
            if (row.parentExternalId() == null) {
                return null;
            }
            Integer parentRow = rowByKey.get(source + "\\u0000" + row.parentExternalId());
            if (parentRow != null) {
                apply(parentRow);
                if (failed.contains(parentRow)) {
                    throw FuhitoException.invalid("import.parent_failed",
                            Map.of("parentExternalId", row.parentExternalId()));
                }
                return unitIdByRow.get(parentRow);
            }
            return orgUnits.findUnitByExternalId(source, row.parentExternalId())
                    .map(OrgUnit::id)
                    .orElseThrow(() -> FuhitoException.invalid("import.parent_unknown",
                            Map.of("parentExternalId", row.parentExternalId())));
        }

        private Outcome applyRow(int index, OrgUnitRow row, String source, String parentId) {
            Optional<OrgUnit> stored = orgUnits.findUnitByExternalId(source, row.externalId());
            if (stored.isEmpty()) {
                OrgUnitState created = orgUnitService.create(new NewOrgUnit(row.code(), row.name(), row.names(),
                        row.shortName(), row.shortNames(), row.phoneticName(), row.unitType(), parentId,
                        row.validFrom() == null ? effectiveDate : row.validFrom(), row.displayOrder(),
                        row.attributes(), row.externalId(), source));
                String id = created.unit().id();
                unitIdByRow.put(index, id);
                written(id, true, "", created);
                if (row.validTo() != null) {
                    written(id, false, "/end", orgUnitService.end(id, row.validTo()));
                }
                return Outcome.CREATED;
            }
            String id = stored.get().id();
            unitIdByRow.put(index, id);
            OrgUnitState before = orgUnitService.get(id, effectiveDate);
            boolean changed = false;
            if (!sameDetails(row, before)) {
                written(id, false, "", orgUnitService.changeDetails(id, new OrgUnitChange(effectiveDate, row.name(),
                        row.names(), row.shortName(), row.shortNames(), row.phoneticName(), row.unitType(),
                        row.displayOrder(), row.attributes())));
                changed = true;
            }
            if (!Objects.equals(parentId, before.parentId())) {
                written(id, false, "/parent", orgUnitService.move(id, parentId, effectiveDate));
                changed = true;
            }
            if (row.validTo() != null && !row.validTo().equals(lastDay(id))) {
                written(id, false, "/end", orgUnitService.end(id, row.validTo()));
                changed = true;
            }
            return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
        }

        private String lastDay(String id) {
            return Objects.toString(orgUnits.versions(id).getLast().period().to(), null);
        }

        private boolean sameDetails(OrgUnitRow row, OrgUnitState before) {
            var version = before.version();
            return Checks.name("name", row.name()).equals(version.name())
                    && Checks.localizedNames("names", row.names()).equals(version.names())
                    && Objects.equals(Checks.optionalName("shortName", row.shortName()), version.shortName())
                    && Checks.localizedNames("shortNames", row.shortNames()).equals(version.shortNames())
                    && Objects.equals(Checks.optionalName("phoneticName", row.phoneticName()),
                            version.phoneticName())
                    && Objects.equals(row.unitType(), before.unitType())
                    && (row.displayOrder() == null ? 0 : row.displayOrder()) == version.displayOrder()
                    && (row.attributes() == null ? Map.of() : row.attributes()).equals(version.attributes());
        }

        private void written(String id, boolean created, String suffix, OrgUnitState state) {
            report.write(new Write("FUHITO_ORG_UNIT", id, created, "fuhito/org-units/" + id + suffix, state));
        }
    }
}
