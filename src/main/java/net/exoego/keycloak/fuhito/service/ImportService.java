package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.function.Consumer;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.LongWrite;
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
    private final Records records;
    private final LongWrite longWrite;

    /**
     * The services that an import of the records about people needs.
     *
     * @param reportingLines the operations on the reporting lines of the realm
     * @param unitRoles the operations on the roles over org units of the realm
     * @param qualifications the operations on the qualifications of the realm
     * @param externalIds the operations on the IDs of other systems of the realm
     * @param changeOrders the operations on the change orders of the realm
     */
    public record Records(ReportingLineService reportingLines, UnitRoleService unitRoles,
            QualificationService qualifications, ExternalIdService externalIds, ChangeOrderService changeOrders) {
    }

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
     * @param records what an import of the records about people needs
     * @param longWrite tells the storage when each row is done
     */
    public ImportService(RefItemService refItemService, RefItemStore refItems, OrgUnitService orgUnitService,
            OrgUnitStore orgUnits, People people, Setup setup, Organizations organizations, Records records,
            LongWrite longWrite) {
        this.longWrite = longWrite;
        this.organizations = organizations;
        this.records = records;
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

    Records records() {
        return records;
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
        return longWrite.run(() -> applyLists(document));
    }

    private ImportReport applyLists(ImportDocument document) {
        Runnable rowDone = longWrite::rowDone;
        LocalDate effectiveDate = Checks.date("effectiveDate", document.effectiveDate());
        String source = Checks.optionalCode("source", document.source());
        String defaultSource = source == null ? DEFAULT_SOURCE : source;
        ImportReport report = new ImportReport();
        if (document.changeOrders() != null) {
            new ChangeOrderImport(records.changeOrders(), people.users(), records.externalIds(),
                    document.effectiveDate(), defaultSource, report, rowDone).run(document.changeOrders());
        }
        SetupImport setupImport = new SetupImport(setup, refItemService, refItems, records.externalIds(),
                effectiveDate, defaultSource, report, rowDone);
        if (document.refKinds() != null) {
            setupImport.refKinds(document.refKinds());
        }
        if (document.attributeDefinitions() != null) {
            setupImport.attributeDefinitions(document.attributeDefinitions());
        }
        if (document.refItems() != null) {
            setupImport.refItems(document.refItems());
        }
        if (document.hierarchies() != null) {
            setupImport.hierarchies(document.hierarchies());
        }
        if (document.orgUnits() != null) {
            new OrgUnitImport(orgUnitService, orgUnits, records.externalIds(), organizations,
                    document.effectiveDate(), defaultSource, document.orgUnits(), report, rowDone).run();
        }
        if (document.orgUnitParents() != null) {
            new ParentImport(orgUnitService, orgUnits, records.externalIds(), document.effectiveDate(),
                    defaultSource, report, rowDone).run(document.orgUnitParents());
        }
        PeopleImport peopleImport =
                new PeopleImport(people, records.externalIds(), orgUnits, document.effectiveDate(), defaultSource,
                        report, rowDone);
        if (document.people() != null) {
            peopleImport.people(document.people());
        }
        if (document.qualifications() != null) {
            new QualificationImport(records.qualifications(), people, records.externalIds(),
                    document.effectiveDate(), defaultSource, report, rowDone).run(document.qualifications());
        }
        if (document.employments() != null) {
            peopleImport.employments(document.employments());
        }
        if (document.assignments() != null) {
            peopleImport.assignments(document.assignments());
        }
        if (document.unitRoles() != null) {
            new UnitRoleImport(records.unitRoles(), records.externalIds(), document.effectiveDate(), defaultSource,
                    report, rowDone).run(document.unitRoles());
        }
        if (document.reportingLines() != null) {
            new ReportingLineImport(records.reportingLines(), people, records.externalIds(),
                    document.effectiveDate(), defaultSource, report, rowDone).run(document.reportingLines());
        }
        if (document.externalIds() != null) {
            new ExternalIdImport(records.externalIds(), defaultSource, report, rowDone)
                    .run(document.externalIds());
        }
        return report;
    }
}
