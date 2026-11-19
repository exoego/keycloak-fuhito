package net.exoego.keycloak.fuhito.rest;

import java.io.InputStream;
import java.time.Clock;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaAttributeDefinitionStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeOrderStore;
import net.exoego.keycloak.fuhito.jpa.JpaEffectiveChangeStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaExternalIdStore;
import net.exoego.keycloak.fuhito.jpa.JpaHierarchyStore;
import net.exoego.keycloak.fuhito.jpa.JpaJobRunStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaPersonStore;
import net.exoego.keycloak.fuhito.jpa.JpaRealmExportStore;
import net.exoego.keycloak.fuhito.jpa.JpaRealmKindStore;
import net.exoego.keycloak.fuhito.jpa.JpaQualificationStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.jpa.JpaResourceRemoval;
import net.exoego.keycloak.fuhito.jpa.JpaReportingLineStore;
import net.exoego.keycloak.fuhito.jpa.JpaUnitRoleStore;
import net.exoego.keycloak.fuhito.jpa.JpaUserDataMasker;
import net.exoego.keycloak.fuhito.jpa.JpaWriteScopes;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.AttributeDefinitionService;
import net.exoego.keycloak.fuhito.service.AttributeSyncService;
import net.exoego.keycloak.fuhito.service.ChangeLogService;
import net.exoego.keycloak.fuhito.service.ChangeOrderService;
import net.exoego.keycloak.fuhito.service.EffectiveChangeService;
import net.exoego.keycloak.fuhito.service.CsvExportService;
import net.exoego.keycloak.fuhito.service.CsvImportService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.HierarchyService;
import net.exoego.keycloak.fuhito.service.ImportService;
import net.exoego.keycloak.fuhito.service.LegalEntityEmployers;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.OrganizationLinkService;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.LeaveService;
import net.exoego.keycloak.fuhito.service.ProjectionService;
import net.exoego.keycloak.fuhito.service.PromotionService;
import net.exoego.keycloak.fuhito.service.RealmExportService;
import net.exoego.keycloak.fuhito.service.RealmKindService;
import net.exoego.keycloak.fuhito.service.QualificationService;
import net.exoego.keycloak.fuhito.service.RefItemService;
import net.exoego.keycloak.fuhito.service.RemovalService;
import net.exoego.keycloak.fuhito.service.ReorganizationService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;
import net.exoego.keycloak.fuhito.service.Today;
import net.exoego.keycloak.fuhito.service.UnitRoleService;
import net.exoego.keycloak.fuhito.service.UnitDays;
import net.exoego.keycloak.fuhito.service.UserMaskingService;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import net.exoego.keycloak.fuhito.store.UserAttributeSource;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import net.exoego.keycloak.fuhito.store.WriteScopes;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The root of the admin REST API for one realm.
 */
@SecurityScheme(securitySchemeName = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT", description = "An access token for the admin API of Keycloak.")
public class FuhitoResource {

    /**
     * Runs work that is too large for the transaction of the request.
     */
    public interface Pieces {

        /**
         * Runs one piece with a resource of its own, bound to a session and a transaction of its own.
         * The writes of the piece are recorded in that transaction.
         *
         * @param <T> the type of the result
         * @param rollBack true to undo the writes of the piece at its end
         * @param piece the piece
         * @return the result of the piece
         */
        <T> T inNewTransaction(boolean rollBack, Function<FuhitoResource, T> piece);

        /**
         * Runs a whole job, unless another run of it for the realm is going on.
         *
         * @param <T> the type of the result
         * @param job the name of the job, which is also the start of the error code
         * @param record true to keep the state of the run as the last run of the job. A dry run is not kept.
         * @param run the job
         * @return the result of the job
         */
        <T> T alone(String job, boolean record, Supplier<T> run);
    }

    private static final String VERSION = loadVersion();

    private final String realmId;
    private final RequestContext context;
    private final EntityManager entityManager;
    private final Clock clock;
    private final BusinessDay.Rule rule;
    private final UserDirectory users;
    private final OrganizationDirectory organizations;
    private final UserAttributeSource userAttributes;
    private final Consumer<String> erasePerson;
    private final ProjectionService.Work projectionWork;
    private final Pieces pieces;

    /**
     * Creates the resource for one request.
     *
     * @param realmId the ID of the realm of the request
     * @param context the request and its caller
     * @param entityManager the entity manager of the request
     * @param rule the time zone and the day start of the realm
     * @param users the users of the realm
     * @param organizations the Keycloak organizations of the realm
     * @param userAttributes the user attributes that an identity provider fills
     * @param erasePerson removes every row about one person
     * @param projectionWork runs each piece of a rebuild of the projection in a transaction of its own
     * @param pieces runs each piece of a sync in a transaction of its own
     */
    public FuhitoResource(String realmId, RequestContext context, EntityManager entityManager, BusinessDay.Rule rule,
            UserDirectory users,
            OrganizationDirectory organizations,
            UserAttributeSource userAttributes, Consumer<String> erasePerson,
            ProjectionService.Work projectionWork, Pieces pieces) {
        this.projectionWork = projectionWork;
        this.pieces = pieces;
        this.userAttributes = userAttributes;
        this.erasePerson = erasePerson;
        this.organizations = organizations;
        this.realmId = realmId;
        this.context = context;
        this.entityManager = entityManager;
        this.rule = rule;
        // The date of this clock is the date of the realm. Its instant is not a time stamp.
        this.clock = rule.clock(Clock.systemUTC());
        // A removed user who still has a person is known to the reads.
        this.users = new UserDirectory() {
            @Override
            public boolean exists(String userId) {
                return users.exists(userId);
            }

            @Override
            public Optional<String> findIdByUsername(String username) {
                return users.findIdByUsername(username);
            }

            @Override
            public Optional<String> username(String userId) {
                return users.username(userId);
            }

            @Override
            public Optional<String> displayName(String userId) {
                return users.displayName(userId);
            }

            @Override
            public boolean clearName(String userId) {
                return users.clearName(userId);
            }

            @Override
            public boolean isKnown(String userId) {
                return users.exists(userId)
                        || new JpaPersonStore(entityManager, realmId).findByUser(userId).isPresent();
            }
        };
    }

    /**
     * Returns the assignments of the realm.
     *
     * @return the sub-resource
     */
    @Path("assignments")
    public AssignmentsResource assignments() {
        return new AssignmentsResource(context, assignmentService(), externalIdService(), membershipService(),
                removals(), writeScopes());
    }

    /**
     * Returns the change orders of the realm.
     *
     * @return the sub-resource
     */
    @Path("change-orders")
    public ChangeOrdersResource changeOrders() {
        return new ChangeOrdersResource(context, changeOrderService(), changeLogService());
    }

    private ChangeOrderService changeOrderService() {
        return new ChangeOrderService(new JpaChangeOrderStore(entityManager, realmId),
                new JpaChangeLogStore(entityManager, realmId), users, Clock.systemUTC());
    }

    /**
     * Returns the change log of the realm.
     *
     * @return the sub-resource
     */
    @Path("change-log")
    public ChangeLogResource changeLog() {
        return new ChangeLogResource(context, changeLogService());
    }

    /**
     * Returns what takes effect on which date in the realm.
     *
     * @return the sub-resource
     */
    @Path("effective-changes")
    public EffectiveChangesResource effectiveChanges() {
        return new EffectiveChangesResource(context,
                new EffectiveChangeService(new JpaEffectiveChangeStore(entityManager, realmId), clock));
    }

    private ChangeLogService changeLogService() {
        return new ChangeLogService(new JpaChangeLogStore(entityManager, realmId),
                new JpaChangeOrderStore(entityManager, realmId));
    }

    /**
     * Returns the employments of the realm.
     *
     * @return the sub-resource
     */
    @Path("employments")
    public EmploymentsResource employments() {
        return new EmploymentsResource(context, employmentService(), externalIdService(), personService(),
                assignmentService(),
                orgUnitService(), new PromotionService(employmentService(), assignmentService(),
                        new JpaAssignmentStore(entityManager, realmId)), new LeaveService(employmentService()),
                removals(), today(), writeScopes());
    }

    /**
     * Returns the shortcut that adds a person to an org unit in one call.
     *
     * @return the sub-resource
     */
    @Path("staff")
    public StaffResource staff() {
        return new StaffResource(context, employmentService(), assignmentService(), personService(),
                externalIdService());
    }

    /**
     * Returns the reference items of the realm.
     *
     * @return the sub-resource
     */
    @Path("ref-items")
    public RefItemsResource refItems() {
        return new RefItemsResource(context, refItemService(), new StructureLimit(context, today()));
    }

    private RefItemService refItemService() {
        return new RefItemService(new JpaRefItemStore(entityManager, realmId), attributeRules(),
                realmKinds()::isKnown, today());
    }

    /**
     * Returns the kinds of reference data of the realm.
     *
     * @return the sub-resource
     */
    @Path("ref-kinds")
    public RefKindsResource refKinds() {
        return new RefKindsResource(context, realmKinds());
    }

    private RealmKindService realmKinds() {
        return new RealmKindService(new JpaRealmKindStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId));
    }

    /**
     * Returns the definitions of free attributes of the realm.
     *
     * @return the sub-resource
     */
    @Path("attribute-definitions")
    public AttributeDefinitionsResource attributeDefinitions() {
        return new AttributeDefinitionsResource(context, attributeRules());
    }

    /**
     * Returns the IDs that other systems have for the resources of the realm.
     *
     * @return the sub-resource
     */
    @Path("external-ids")
    public ExternalIdsResource externalIds() {
        return new ExternalIdsResource(context, externalIdService(), orgUnitService(),
                new StructureLimit(context, today()));
    }

    private ExternalIdService externalIdService() {
        return new ExternalIdService(new JpaExternalIdStore(entityManager, realmId));
    }

    /**
     * Returns the export of every stored row of the realm.
     *
     * @return the sub-resource
     */
    @Path("export")
    public ExportResource export() {
        return new ExportResource(context, realmExport());
    }

    /**
     * Returns the restore of an export into the realm.
     *
     * @return the sub-resource
     */
    @Path("restore")
    public RestoreResource restore() {
        return new RestoreResource(context, realmExport(), realmId);
    }

    private RealmExportService realmExport() {
        return new RealmExportService(new RealmExportService.Stores(
                new JpaRealmExportStore(entityManager, realmId), new JpaRealmKindStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), new JpaAttributeDefinitionStore(entityManager, realmId),
                new JpaHierarchyStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                new JpaPersonStore(entityManager, realmId), new JpaEmploymentStore(entityManager, realmId),
                new JpaAssignmentStore(entityManager, realmId), new JpaReportingLineStore(entityManager, realmId),
                new JpaUnitRoleStore(entityManager, realmId), new JpaQualificationStore(entityManager, realmId),
                new JpaExternalIdStore(entityManager, realmId), new JpaChangeOrderStore(entityManager, realmId),
                users, organizations));
    }

    private WriteScopes writeScopes() {
        return new JpaWriteScopes(entityManager, realmId);
    }

    private RemovalService removals() {
        return new RemovalService(new JpaResourceRemoval(entityManager, realmId));
    }

    private AttributeDefinitionService attributeRules() {
        return new AttributeDefinitionService(new JpaAttributeDefinitionStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), realmKinds()::isKnown);
    }

    private AssignmentService assignmentService() {
        return new AssignmentService(new JpaAssignmentStore(entityManager, realmId),
                new JpaExternalIdStore(entityManager, realmId), new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), new JpaReportingLineStore(entityManager, realmId),
                today(), attributeRules());
    }

    private EmploymentService employmentService() {
        return new EmploymentService(new JpaEmploymentStore(entityManager, realmId),
                new JpaExternalIdStore(entityManager, realmId), new JpaAssignmentStore(entityManager, realmId), new JpaRefItemStore(entityManager, realmId),
                new JpaPersonStore(entityManager, realmId), new JpaReportingLineStore(entityManager, realmId),
                new JpaUnitRoleStore(entityManager, realmId),
                new LegalEntityEmployers(new JpaOrgUnitStore(entityManager, realmId),
                        new JpaRefItemStore(entityManager, realmId)), attributeRules());
    }

    /**
     * Returns the reporting lines of the realm.
     *
     * @return the sub-resource
     */
    @Path("reporting-lines")
    public ReportingLinesResource reportingLines() {
        return new ReportingLinesResource(context, reportingLineService(), personService());
    }

    /**
     * Returns the roles over org units of the realm.
     *
     * @return the sub-resource
     */
    @Path("unit-roles")
    public UnitRolesResource unitRoles() {
        return new UnitRolesResource(context, unitRoleService());
    }

    private UnitRoleService unitRoleService() {
        return new UnitRoleService(new JpaUnitRoleStore(entityManager, realmId), new UnitRoleService.Sources(
                new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId)), today());
    }

    private ReportingLineService reportingLineService() {
        return new ReportingLineService(new JpaReportingLineStore(entityManager, realmId),
                new ReportingLineService.Sources(new JpaAssignmentStore(entityManager, realmId),
                        new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                        new JpaRefItemStore(entityManager, realmId), users,
                        new JpaPersonStore(entityManager, realmId)),
                clock);
    }

    /**
     * Returns the org units of the realm.
     *
     * @return the sub-resource
     */
    @Path("org-units")
    public OrgUnitsResource orgUnits() {
        return new OrgUnitsResource(context, orgUnitService(), externalIdService(), membershipService(),
                new OrganizationLinkService(new JpaOrgUnitStore(entityManager, realmId), organizations),
                unitRoleService(), removals(), new StructureLimit(context, today()));
    }

    /**
     * Returns the endpoint that changes many org units on one date.
     *
     * @return the sub-resource
     */
    @Path("reorganizations")
    public ReorganizationsResource reorganizations() {
        return new ReorganizationsResource(context, new ReorganizationService(orgUnitService()),
                externalIdService());
    }

    /**
     * Returns the projection into the groups of Keycloak organizations.
     *
     * @return the sub-resource
     */
    @Path("projection")
    public ProjectionResource projection() {
        return new ProjectionResource(context, new ProjectionService(projectionWork),
                new JpaJobRunStore(entityManager, realmId));
    }

    /**
     * Returns the sync from user attributes.
     *
     * @return the sub-resource
     */
    @Path("attribute-sync")
    public AttributeSyncResource attributeSync() {
        return new AttributeSyncResource(context, dryRun -> new AttributeSyncService(new AttributeSyncService.Work() {
            @Override
            public <T> T inNewTransaction(Function<AttributeSyncService.Parts, T> piece) {
                return pieces.inNewTransaction(dryRun, resource -> piece.apply(resource.syncParts()));
            }

            @Override
            public <T> T alone(Supplier<T> run) {
                return pieces.alone("sync", !dryRun, run);
            }
        }), new JpaJobRunStore(entityManager, realmId));
    }

    private AttributeSyncService.Parts syncParts() {
        ExternalIdService externalIds = externalIdService();
        return new AttributeSyncService.Parts(new AttributeSyncService.Sources(
                userAttributes, users, new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), new JpaEmploymentStore(entityManager, realmId),
                employmentService(), new JpaAssignmentStore(entityManager, realmId), assignmentService(),
                new JpaReportingLineStore(entityManager, realmId), reportingLineService(), personService()),
                today(), write -> Writes.record(context, externalIds, write));
    }

    /**
     * Returns the endpoint that loads many rows in one request.
     *
     * @return the sub-resource
     */
    @Path("imports")
    public ImportsResource imports() {
        var refItemStore = new JpaRefItemStore(entityManager, realmId);
        var orgUnitStore = new JpaOrgUnitStore(entityManager, realmId);
        var people = new ImportService.People(employmentService(), new JpaEmploymentStore(entityManager, realmId),
                assignmentService(), new JpaAssignmentStore(entityManager, realmId), users, personService());
        var importService = new ImportService(refItemService(), refItemStore,
                orgUnitService(), orgUnitStore, people, new ImportService.Setup(realmKinds(), attributeRules(),
                        new HierarchyService(new JpaHierarchyStore(entityManager, realmId))),
                new ImportService.Organizations(new OrganizationLinkService(orgUnitStore, organizations),
                        organizations, context::requireManageOrganization),
                new ImportService.Records(reportingLineService(), unitRoleService(), qualificationService(),
                        externalIdService(), changeOrderService()));
        var definitions = new JpaAttributeDefinitionStore(entityManager, realmId);
        return new ImportsResource(context, importService, new CsvImportService(importService, refItemStore,
                orgUnitService(), people, definitions::list),
                new CsvExportService(refItemService(), () -> realmKinds().list(), orgUnitService(), personService(),
                        employmentService(), assignmentService(), today(),
                        new HierarchyService(new JpaHierarchyStore(entityManager, realmId)), organizations,
                        reportingLineService(), unitRoleService(), qualificationService(), externalIdService(),
                        changeOrderService(), definitions::list), externalIdService());
    }

    private OrgUnitService orgUnitService() {
        return new OrgUnitService(new JpaOrgUnitStore(entityManager, realmId),
                new JpaExternalIdStore(entityManager, realmId), new JpaAssignmentStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), today(), attributeRules());
    }

    // A new one for each service, so that none of them sees the tree as another one loaded it.
    private Today today() {
        return new UnitDays(new JpaOrgUnitStore(entityManager, realmId), rule, Clock.systemUTC());
    }

    /**
     * Returns what is stored about the users of the realm.
     *
     * @return the sub-resource
     */
    @Path("users")
    public UsersResource users() {
        return new UsersResource(context, reads(), users, personService(), personData());
    }

    private UsersResource.Reads reads() {
        return new UsersResource.Reads(membershipService(), employmentService(), assignmentService(),
                qualificationService(), reportingLineService(), unitRoleService(), externalIdService());
    }

    private PersonData personData() {
        return new PersonData(context, new UserMaskingService(new JpaUserDataMasker(entityManager, realmId),
                new JpaEmploymentStore(entityManager, realmId), clock, Clock.systemUTC()), users, erasePerson,
                personService(), writeScopes());
    }

    /**
     * Returns the people of the realm.
     *
     * @return the sub-resource
     */
    @Path("people")
    public PeopleResource people() {
        return new PeopleResource(context, personService(), reads(), personData());
    }

    private PersonService personService() {
        return new PersonService(new JpaPersonStore(entityManager, realmId),
                new JpaExternalIdStore(entityManager, realmId), users,
                personId -> !new JpaEmploymentStore(entityManager, realmId).findByPerson(personId).isEmpty()
                        || !new JpaQualificationStore(entityManager, realmId).findByPerson(personId).isEmpty()
                        || !new JpaReportingLineStore(entityManager, realmId).findByManager(personId).isEmpty(),
                attributeRules());
    }

    /**
     * Returns the qualifications of the realm.
     *
     * @return the sub-resource
     */
    @Path("qualifications")
    public QualificationsResource qualifications() {
        return new QualificationsResource(context, qualificationService(), personService(), externalIdService());
    }

    private QualificationService qualificationService() {
        return new QualificationService(new JpaQualificationStore(entityManager, realmId),
                new JpaExternalIdStore(entityManager, realmId), new JpaRefItemStore(entityManager, realmId), new JpaPersonStore(entityManager, realmId), users,
                clock, attributeRules());
    }

    private MembershipService membershipService() {
        return new MembershipService(new JpaAssignmentStore(entityManager, realmId),
                new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), users, today());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "getVersion", summary = "Returns the version of this extension.")
    @Tag(ref = Tags.GENERAL)
    @APIResponse(responseCode = "200", description = "The version.")
    @ApiErrors
    public VersionRepresentation version() {
        context.requireReadStructure();
        return new VersionRepresentation(VERSION);
    }

    /**
     * Returns the hierarchies of the realm.
     *
     * @return the sub-resource
     */
    @Path("hierarchies")
    public HierarchiesResource hierarchies() {
        return new HierarchiesResource(context,
                new HierarchyService(new JpaHierarchyStore(entityManager, realmId)));
    }

    private static String loadVersion() {
        return Unchecked.io(() -> {
            Properties properties = new Properties();
            try (InputStream in = FuhitoResource.class.getResourceAsStream("/META-INF/fuhito/version.properties")) {
                properties.load(in);
            }
            return properties.getProperty("version");
        });
    }
}
