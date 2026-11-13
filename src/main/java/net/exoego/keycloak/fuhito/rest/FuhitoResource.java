package net.exoego.keycloak.fuhito.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Properties;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.jpa.HierarchyEntity;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeOrderStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaQualificationStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.jpa.JpaReportingLineStore;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.ChangeLogService;
import net.exoego.keycloak.fuhito.service.ChangeOrderService;
import net.exoego.keycloak.fuhito.service.CsvImportService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.ImportService;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.OrganizationLinkService;
import net.exoego.keycloak.fuhito.service.QualificationService;
import net.exoego.keycloak.fuhito.service.RefItemService;
import net.exoego.keycloak.fuhito.service.ReorganizationService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * The root of the admin REST API for one realm.
 */
public class FuhitoResource {

    private static final String VERSION = loadVersion();

    private final String realmId;
    private final RequestContext context;
    private final EntityManager entityManager;
    private final UserDirectory users;
    private final OrganizationDirectory organizations;

    /**
     * Creates the resource for one request.
     *
     * @param realmId the ID of the realm of the request
     * @param context the request and its caller
     * @param entityManager the entity manager of the request
     * @param users the users of the realm
     * @param organizations the Keycloak organizations of the realm
     */
    public FuhitoResource(String realmId, RequestContext context, EntityManager entityManager, UserDirectory users,
            OrganizationDirectory organizations) {
        this.organizations = organizations;
        this.realmId = realmId;
        this.context = context;
        this.entityManager = entityManager;
        this.users = users;
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
        return new EmploymentsResource(context, employmentService());
    }

    /**
     * Returns the shortcut that adds a person to an org unit in one call.
     *
     * @return the sub-resource
     */
    @Path("staff")
    public StaffResource staff() {
        return new StaffResource(context, employmentService(), assignmentService());
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
                new JpaAssignmentStore(entityManager, realmId), new JpaRefItemStore(entityManager, realmId), users,
                new JpaReportingLineStore(entityManager, realmId));
    }

    /**
     * Returns the reporting lines of the realm.
     *
     * @return the sub-resource
     */
    @Path("reporting-lines")
    public ReportingLinesResource reportingLines() {
        return new ReportingLinesResource(context, reportingLineService());
    }

    private ReportingLineService reportingLineService() {
        return new ReportingLineService(new JpaReportingLineStore(entityManager, realmId),
                new ReportingLineService.Sources(new JpaAssignmentStore(entityManager, realmId),
                        new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                        new JpaRefItemStore(entityManager, realmId), users),
                Clock.systemDefaultZone());
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
     * Returns the endpoint that loads many rows in one request.
     *
     * @return the sub-resource
     */
    @Path("imports")
    public ImportsResource imports() {
        var refItemStore = new JpaRefItemStore(entityManager, realmId);
        var orgUnitStore = new JpaOrgUnitStore(entityManager, realmId);
        var people = new ImportService.People(employmentService(), new JpaEmploymentStore(entityManager, realmId),
                assignmentService(), new JpaAssignmentStore(entityManager, realmId), users);
        var importService = new ImportService(new RefItemService(refItemStore), refItemStore, orgUnitService(),
                orgUnitStore, people);
        return new ImportsResource(context, importService,
                new CsvImportService(importService, refItemStore, orgUnitStore, orgUnitService(), people));
    }

    /**
     * Returns the export of the realm.
     *
     * @return the sub-resource
     */
    @Path("export")
    public ExportResource export() {
        return new ExportResource(context, () -> new ExportResource.Sources(
                new RefItemService(new JpaRefItemStore(entityManager, realmId)), orgUnitService(),
                new JpaOrgUnitStore(entityManager, realmId), employmentService(),
                new JpaEmploymentStore(entityManager, realmId), assignmentService(),
                new JpaAssignmentStore(entityManager, realmId),
                new ChangeOrderService(new JpaChangeOrderStore(entityManager, realmId),
                        new JpaChangeLogStore(entityManager, realmId), users, Clock.systemUTC())),
                Clock.systemUTC());
    }

    private OrgUnitService orgUnitService() {
        return new OrgUnitService(new JpaOrgUnitStore(entityManager, realmId),
                new JpaAssignmentStore(entityManager, realmId), new JpaRefItemStore(entityManager, realmId),
                Clock.systemDefaultZone());
    }

    /**
     * Returns what is stored about the users of the realm.
     *
     * @return the sub-resource
     */
    @Path("users")
    public UsersResource users() {
        return new UsersResource(context, membershipService(), employmentService(), assignmentService(),
                qualificationService(), reportingLineService());
    }

    /**
     * Returns the qualifications of the realm.
     *
     * @return the sub-resource
     */
    @Path("qualifications")
    public QualificationsResource qualifications() {
        return new QualificationsResource(context, qualificationService());
    }

    private QualificationService qualificationService() {
        return new QualificationService(new JpaQualificationStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), users, Clock.systemDefaultZone());
    }

    private MembershipService membershipService() {
        return new MembershipService(new JpaAssignmentStore(entityManager, realmId),
                new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), users, Clock.systemDefaultZone());
    }

    /**
     * Returns the version of this extension.
     *
     * @return the version
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public VersionRepresentation version() {
        context.requireRead();
        return new VersionRepresentation(VERSION);
    }

    /**
     * Lists the hierarchies of the realm.
     *
     * @return the hierarchies, ordered by code
     */
    @GET
    @Path("hierarchies")
    @Produces(MediaType.APPLICATION_JSON)
    public List<HierarchyRepresentation> hierarchies() {
        context.requireRead();
        return entityManager
                .createQuery("select h from HierarchyEntity h where h.realmId = :realmId order by h.code",
                        HierarchyEntity.class)
                .setParameter("realmId", realmId)
                .getResultStream()
                .map(h -> new HierarchyRepresentation(h.getId(), h.getCode(), h.getName()))
                .toList();
    }

    private static String loadVersion() {
        Properties properties = new Properties();
        try (InputStream in = FuhitoResource.class.getResourceAsStream("/META-INF/fuhito/version.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version");
    }
}
