package net.exoego.keycloak.fuhito.rest;

import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.jpa.Hierarchies;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeOrderStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaPersonStore;
import net.exoego.keycloak.fuhito.jpa.JpaQualificationStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.jpa.JpaReportingLineStore;
import net.exoego.keycloak.fuhito.jpa.JpaUserDataMasker;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.AttributeSyncService;
import net.exoego.keycloak.fuhito.service.ChangeLogService;
import net.exoego.keycloak.fuhito.service.ChangeOrderService;
import net.exoego.keycloak.fuhito.service.CsvImportService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.ImportService;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.OrganizationLinkService;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.ProjectionService;
import net.exoego.keycloak.fuhito.service.QualificationService;
import net.exoego.keycloak.fuhito.service.RefItemService;
import net.exoego.keycloak.fuhito.service.ReorganizationService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;
import net.exoego.keycloak.fuhito.service.Today;
import net.exoego.keycloak.fuhito.service.UnitDays;
import net.exoego.keycloak.fuhito.service.UserMaskingService;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import net.exoego.keycloak.fuhito.store.OrganizationGroups;
import net.exoego.keycloak.fuhito.store.UserAttributeSource;
import net.exoego.keycloak.fuhito.store.UserDirectory;
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

    private static final String VERSION = loadVersion();

    private final String realmId;
    private final RequestContext context;
    private final EntityManager entityManager;
    private final Clock clock;
    private final BusinessDay.Rule rule;
    private final UserDirectory users;
    private final OrganizationDirectory organizations;
    private final OrganizationGroups organizationGroups;
    private final UserAttributeSource userAttributes;
    private final Consumer<String> erasePerson;

    /**
     * Creates the resource for one request.
     *
     * @param realmId the ID of the realm of the request
     * @param context the request and its caller
     * @param entityManager the entity manager of the request
     * @param rule the time zone and the day start of the realm
     * @param users the users of the realm
     * @param organizations the Keycloak organizations of the realm
     * @param organizationGroups the groups and members of the Keycloak organizations of the realm
     * @param userAttributes the user attributes that an identity provider fills
     * @param erasePerson removes every row and every change log entry about one person
     */
    public FuhitoResource(String realmId, RequestContext context, EntityManager entityManager, BusinessDay.Rule rule,
            UserDirectory users,
            OrganizationDirectory organizations, OrganizationGroups organizationGroups,
            UserAttributeSource userAttributes, Consumer<String> erasePerson) {
        this.userAttributes = userAttributes;
        this.erasePerson = erasePerson;
        this.organizationGroups = organizationGroups;
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
            public Optional<String> displayName(String userId) {
                return users.displayName(userId);
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
        return new AssignmentsResource(context, assignmentService());
    }

    /**
     * Returns the change orders of the realm.
     *
     * @return the sub-resource
     */
    @Path("change-orders")
    public ChangeOrdersResource changeOrders() {
        return new ChangeOrdersResource(context, new ChangeOrderService(
                new JpaChangeOrderStore(entityManager, realmId), new JpaChangeLogStore(entityManager, realmId),
                users, Clock.systemUTC()), changeLogService());
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
        return new EmploymentsResource(context, employmentService(), personService());
    }

    /**
     * Returns the shortcut that adds a person to an org unit in one call.
     *
     * @return the sub-resource
     */
    @Path("staff")
    public StaffResource staff() {
        return new StaffResource(context, employmentService(), assignmentService(), personService());
    }

    /**
     * Returns the reference items of the realm.
     *
     * @return the sub-resource
     */
    @Path("ref-items")
    public RefItemsResource refItems() {
        return new RefItemsResource(context, new RefItemService(new JpaRefItemStore(entityManager, realmId)));
    }

    private AssignmentService assignmentService() {
        return new AssignmentService(new JpaAssignmentStore(entityManager, realmId),
                new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), new JpaReportingLineStore(entityManager, realmId));
    }

    private EmploymentService employmentService() {
        return new EmploymentService(new JpaEmploymentStore(entityManager, realmId),
                new JpaAssignmentStore(entityManager, realmId), new JpaRefItemStore(entityManager, realmId),
                new JpaPersonStore(entityManager, realmId), new JpaReportingLineStore(entityManager, realmId));
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
        return new OrgUnitsResource(context, orgUnitService(), membershipService(),
                new OrganizationLinkService(new JpaOrgUnitStore(entityManager, realmId), organizations));
    }

    /**
     * Returns the endpoint that changes many org units on one date.
     *
     * @return the sub-resource
     */
    @Path("reorganizations")
    public ReorganizationsResource reorganizations() {
        return new ReorganizationsResource(context, new ReorganizationService(orgUnitService()));
    }

    /**
     * Returns the projection into the groups of Keycloak organizations.
     *
     * @return the sub-resource
     */
    @Path("projection")
    public ProjectionResource projection() {
        return new ProjectionResource(context, new ProjectionService(new JpaOrgUnitStore(entityManager, realmId),
                new JpaAssignmentStore(entityManager, realmId), new JpaEmploymentStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), organizationGroups, today()));
    }

    /**
     * Returns the sync from user attributes.
     *
     * @return the sub-resource
     */
    @Path("attribute-sync")
    public AttributeSyncResource attributeSync() {
        return new AttributeSyncResource(context, new AttributeSyncService(new AttributeSyncService.Sources(
                userAttributes, users, new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), new JpaEmploymentStore(entityManager, realmId),
                employmentService(), new JpaAssignmentStore(entityManager, realmId), assignmentService(),
                new JpaReportingLineStore(entityManager, realmId), reportingLineService(), personService()),
                today()));
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
        var importService = new ImportService(new RefItemService(refItemStore), refItemStore, orgUnitService(),
                orgUnitStore, people);
        return new ImportsResource(context, importService,
                new CsvImportService(importService, refItemStore, orgUnitStore, orgUnitService(), people));
    }

    private OrgUnitService orgUnitService() {
        return new OrgUnitService(new JpaOrgUnitStore(entityManager, realmId),
                new JpaAssignmentStore(entityManager, realmId), new JpaRefItemStore(entityManager, realmId),
                today());
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
                qualificationService(), reportingLineService());
    }

    private PersonData personData() {
        return new PersonData(context, new UserMaskingService(new JpaUserDataMasker(entityManager, realmId),
                new JpaEmploymentStore(entityManager, realmId), clock, Clock.systemUTC()), erasePerson);
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
        return new PersonService(new JpaPersonStore(entityManager, realmId), users,
                personId -> !new JpaEmploymentStore(entityManager, realmId).findByPerson(personId).isEmpty()
                        || !new JpaQualificationStore(entityManager, realmId).findByPerson(personId).isEmpty()
                        || !new JpaReportingLineStore(entityManager, realmId).findByManager(personId).isEmpty());
    }

    /**
     * Returns the qualifications of the realm.
     *
     * @return the sub-resource
     */
    @Path("qualifications")
    public QualificationsResource qualifications() {
        return new QualificationsResource(context, qualificationService(), personService());
    }

    private QualificationService qualificationService() {
        return new QualificationService(new JpaQualificationStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), new JpaPersonStore(entityManager, realmId), users,
                clock);
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
        context.requireRead();
        return new VersionRepresentation(VERSION);
    }

    @GET
    @Path("hierarchies")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "listHierarchies", summary = "Lists the hierarchies of the realm, ordered by code.")
    @Tag(ref = Tags.GENERAL)
    @APIResponse(responseCode = "200", description = "The hierarchies.")
    @ApiErrors
    public List<HierarchyRepresentation> hierarchies() {
        context.requireRead();
        return Hierarchies.list(entityManager, realmId).stream()
                .map(h -> new HierarchyRepresentation(h.getId(), h.getCode(), h.getName()))
                .toList();
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
