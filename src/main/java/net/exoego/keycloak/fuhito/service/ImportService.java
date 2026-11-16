package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportDocument.AttributeDefinitionRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.HierarchyRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefItemRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefKindRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
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
    private static final String REF_KINDS = "refKinds";
    private static final String ATTRIBUTE_DEFINITIONS = "attributeDefinitions";
    private static final String HIERARCHIES = "hierarchies";

    /**
     * The services that an import of the setup of a realm needs.
     *
     * @param kinds the operations on the kinds of reference data of the realm
     * @param definitions the operations on the definitions of free attributes of the realm
     * @param hierarchies the operations on the hierarchies of the realm
     */
    public record Setup(RealmKindService kinds, AttributeDefinitionService definitions,
            HierarchyService hierarchies) {
    }

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
    private final Setup setup;
    private final Organizations organizations;

    /**
     * What a link of an org unit to a Keycloak organization needs.
     *
     * @param links the operations on the links of the realm
     * @param directory the Keycloak organizations of the realm
     * @param manage checks that the caller may manage an organization, by its ID
     */
    public record Organizations(OrganizationLinkService links, OrganizationDirectory directory,
            Consumer<String> manage) {
    }

    /**
     * Creates the service.
     *
     * @param refItemService the operations on reference items of the realm
     * @param refItems the storage of reference items of the realm
     * @param orgUnitService the operations on org units of the realm
     * @param orgUnits the storage of org units of the realm
     * @param people what an import of employments and assignments needs
     * @param setup what an import of the setup of the realm needs
     * @param organizations what a link to a Keycloak organization needs
     */
    public ImportService(RefItemService refItemService, RefItemStore refItems, OrgUnitService orgUnitService,
            OrgUnitStore orgUnits, People people, Setup setup, Organizations organizations) {
        this.organizations = organizations;
        this.refItemService = refItemService;
        this.refItems = refItems;
        this.orgUnitService = orgUnitService;
        this.orgUnits = orgUnits;
        this.people = people;
        this.setup = setup;
    }

    Setup setup() {
        return setup;
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
        if (document.refKinds() != null) {
            each(REF_KINDS, document.refKinds(), report, row -> importRefKind(row, report));
        }
        if (document.attributeDefinitions() != null) {
            each(ATTRIBUTE_DEFINITIONS, document.attributeDefinitions(), report,
                    row -> importAttributeDefinition(row, report));
        }
        if (document.refItems() != null) {
            each(REF_ITEMS, document.refItems(), report, row -> importRefItem(row, report));
        }
        if (document.hierarchies() != null) {
            each(HIERARCHIES, document.hierarchies(), report, row -> importHierarchy(row, report));
        }
        String defaultSource = source == null ? DEFAULT_SOURCE : source;
        if (document.orgUnits() != null) {
            new OrgUnitImport(orgUnitService, orgUnits, organizations, document.effectiveDate(), defaultSource,
                    document.orgUnits(), report).run();
        }
        if (document.orgUnitParents() != null) {
            new ParentImport(orgUnitService, orgUnits, document.effectiveDate(), defaultSource, report)
                    .run(document.orgUnitParents());
        }
        PeopleImport peopleImport =
                new PeopleImport(people, orgUnits, document.effectiveDate(), defaultSource, report);
        if (document.people() != null) {
            peopleImport.people(document.people());
        }
        if (document.employments() != null) {
            peopleImport.employments(document.employments());
        }
        if (document.assignments() != null) {
            peopleImport.assignments(document.assignments());
        }
        return report;
    }

    private static <R> void each(String list, List<R> rows, ImportReport report, Function<R, Outcome> apply) {
        report.startList(list);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(list, apply.apply(Checks.required("row", rows.get(i))));
            } catch (FuhitoException e) {
                report.error(list, i, e);
            }
        }
    }

    private Outcome importRefKind(RefKindRow row, ImportReport report) {
        String code = Checks.required("code", row.code());
        Optional<RealmKindService.View> stored = setup.kinds().find(code);
        if (stored.isEmpty()) {
            report.write(new Write.OfRefKind(true, setup.kinds().create(code, row.name(), row.names())));
            return Outcome.CREATED;
        }
        RealmKindService.View before = stored.get();
        if (Checks.name("name", row.name()).equals(before.name())
                && Checks.localizedNames("names", row.names()).equals(before.names())) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfRefKind(false, setup.kinds().rename(code, row.name(), row.names())));
        return Outcome.CHANGED;
    }

    private Outcome importAttributeDefinition(AttributeDefinitionRow row, ImportReport report) {
        AttributeDefinitionService definitions = setup.definitions();
        String name = Checks.required("name", row.name());
        Optional<AttributeDefinition> stored = definitions.list(Checks.required("target", row.target())).stream()
                .filter(definition -> definition.name().equals(name))
                .findFirst();
        if (stored.isEmpty()) {
            report.write(new Write.OfAttributeDefinition(true, definitions.create(row.target(), name, row.type(),
                    row.refKind(), row.required())));
            return Outcome.CREATED;
        }
        AttributeDefinition before = stored.get();
        if (before.type().name().equals(row.type()) && Objects.equals(before.refKind(), row.refKind())
                && before.required() == Boolean.TRUE.equals(row.required())) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfAttributeDefinition(false, definitions.replace(before.id(), row.type(),
                row.refKind(), row.required())));
        return Outcome.CHANGED;
    }

    private Outcome importHierarchy(HierarchyRow row, ImportReport report) {
        Optional<Hierarchy> stored = setup.hierarchies().find(Checks.required("code", row.code()));
        if (stored.isEmpty()) {
            report.write(new Write.OfHierarchy(true, setup.hierarchies().create(row.code(), row.name())));
            return Outcome.CREATED;
        }
        if (Checks.name("name", row.name()).equals(stored.get().name())) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfHierarchy(false, setup.hierarchies().rename(stored.get().id(), row.name())));
        return Outcome.CHANGED;
    }

    private Outcome importRefItem(RefItemRow row, ImportReport report) {
        String kind = Checks.required("kind", row.kind());
        String code = Checks.code("code", row.code());
        Optional<RefItem> stored = refItems.findByCode(kind, code);
        if (stored.isEmpty()) {
            RefItemService.Marks marks = new RefItemService.Marks(row.legalEntity(), row.actingHead(), row.group());
            RefItem created = refItemService.create(kind, code, row.name(), row.names(), row.displayOrder(),
                    row.attributes(), marks);
            RefItem item = Boolean.FALSE.equals(row.active())
                    ? refItemService.update(created.id(), row.name(), row.names(), row.displayOrder(),
                            row.attributes(), false, marks)
                    : created;
            report.write(new Write.OfRefItem(true, item));
            return Outcome.CREATED;
        }
        RefItem before = stored.get();
        RefItem wanted = new RefItem(before.id(), kind, code, Checks.name("name", row.name()),
                Checks.localizedNames("names", row.names()), row.displayOrder() == null ? 0 : row.displayOrder(),
                row.attributes() == null ? Map.of() : row.attributes(), row.active() == null || row.active(),
                row.legalEntity() == null ? before.legalEntity() : row.legalEntity(),
                row.actingHead() == null ? before.actingHead() : row.actingHead(),
                row.group() == null ? before.group() : row.group());
        if (wanted.equals(before)) {
            return Outcome.UNCHANGED;
        }
        RefItem item = refItemService.update(before.id(), row.name(), row.names(), row.displayOrder(),
                row.attributes(), row.active(), new RefItemService.Marks(wanted.legalEntity(), wanted.actingHead(),
                        wanted.group()));
        report.write(new Write.OfRefItem(false, item));
        return Outcome.CHANGED;
    }
}
