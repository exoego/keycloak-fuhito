package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
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
            AssignmentService assignmentService, AssignmentStore assignments, UserDirectory users,
            PersonService personService) {
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
            new OrgUnitImport(orgUnitService, orgUnits, document.effectiveDate(), defaultSource,
                    document.orgUnits(), report).run();
        }
        PeopleImport peopleImport =
                new PeopleImport(people, orgUnits, document.effectiveDate(), defaultSource, report);
        if (document.employments() != null) {
            peopleImport.employments(document.employments());
        }
        if (document.assignments() != null) {
            peopleImport.assignments(document.assignments());
        }
        return report;
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
            report.write(new Write.OfRefItem(true, item));
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
        report.write(new Write.OfRefItem(false, item));
        return Outcome.CHANGED;
    }
}
