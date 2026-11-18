package net.exoego.keycloak.fuhito.keycloak;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Messages;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeOrderStore;
import net.exoego.keycloak.fuhito.jpa.JpaIdempotencyStore;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
import net.exoego.keycloak.fuhito.rest.ErrorRepresentation;
import net.exoego.keycloak.fuhito.rest.RequestContext;
import net.exoego.keycloak.fuhito.store.IdempotencyStore;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * The request and its caller, as Keycloak knows them.
 */
final class KeycloakRequestContext implements RequestContext {

    /**
     * The query parameter that names the change order of a write.
     */
    static final String CHANGE_ORDER_PARAMETER = "changeOrder";

    /**
     * The realm attribute with the number of days after today that a caller who may read only the org structure
     * may read it on.
     */
    static final String DAYS_AHEAD_ATTRIBUTE = "fuhito.structure.daysAhead";

    private final KeycloakSession session;
    private final RealmModel realm;
    private final AdminPermissionEvaluator auth;
    private final AdminEventBuilder adminEvent;
    private final EntityManager entityManager;
    private final FuhitoException timeSettingsError;
    private boolean writing;

    KeycloakRequestContext(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
            AdminEventBuilder adminEvent, EntityManager entityManager, FuhitoException timeSettingsError) {
        this.timeSettingsError = timeSettingsError;
        this.session = session;
        this.realm = realm;
        this.auth = auth;
        this.adminEvent = adminEvent;
        this.entityManager = entityManager;
    }

    @Override
    public void requireRead() {
        requireRole(FuhitoRoles.VIEW_PEOPLE, FuhitoRoles.MANAGE);
        requireTimeSettings();
    }

    @Override
    public void requireReadStructure() {
        requireRole(FuhitoRoles.VIEW_STRUCTURE, FuhitoRoles.VIEW_PEOPLE, FuhitoRoles.MANAGE);
        requireTimeSettings();
    }

    // Keycloak picks the client of the roles by the realm of the caller, the same as for its own admin roles.
    private void requireRole(String... roles) {
        if (!auth.hasOneAdminRole(roles)) {
            throw new ForbiddenException();
        }
    }

    @Override
    public OptionalInt structureDaysAhead() {
        String setting = realm.getAttribute(DAYS_AHEAD_ATTRIBUTE);
        if (setting == null || auth.hasOneAdminRole(FuhitoRoles.VIEW_PEOPLE, FuhitoRoles.MANAGE)) {
            return OptionalInt.empty();
        }
        int days;
        try {
            days = Integer.parseInt(setting.strip());
        } catch (NumberFormatException e) {
            days = -1;
        }
        if (days < 0) {
            throw failure(error(Response.Status.CONFLICT, "structure.days_ahead_invalid", Map.of("value", setting)));
        }
        return OptionalInt.of(days);
    }

    // Every date in a response depends on what today is, so a realm must say where it is first.
    private void requireTimeSettings() {
        if (timeSettingsError != null) {
            throw failure(error(Response.Status.CONFLICT, timeSettingsError.code(), timeSettingsError.params()));
        }
    }

    private Response error(Response.Status status, String code, Map<String, String> params) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorRepresentation(code, params, Messages.format(code, params, locale())))
                .build();
    }

    @Override
    public void requireWrite() {
        requireRole(FuhitoRoles.MANAGE);
        writing = true;
        requireTimeSettings();
        String changeOrderId = changeOrderId();
        if (changeOrderId != null
                && new JpaChangeOrderStore(entityManager, realm.getId()).find(changeOrderId).isEmpty()) {
            throw failure(error(Response.Status.BAD_REQUEST, "change_order.not_found",
                    Map.of("id", changeOrderId)));
        }
    }

    @Override
    public void requireManageUser(String userId) {
        UserModel user = session.users().getUserById(realm, userId);
        if (user != null) {
            auth.users().requireManage(user);
        }
    }

    @Override
    public void requireManageGroups() {
        auth.groups().requireManage();
    }

    @Override
    public void requireManageOrganization(String organizationId) {
        OrganizationProvider provider = session.getProvider(OrganizationProvider.class);
        OrganizationModel organization = provider == null || organizationId == null ? null
                : provider.getById(organizationId);
        if (organization != null) {
            auth.orgs().requireManage(organization);
        }
    }

    @Override
    public String header(String name) {
        return session.getContext().getHttpRequest().getHttpHeaders().getHeaderString(name);
    }

    @Override
    public String requestPath() {
        return session.getContext().getUri().getPath();
    }

    @Override
    public void lockTarget() {
        // The path is /admin/realms/{realm}/fuhito/{collection}/{id}/...
        List<String> segments = List.of(requestPath().split("/"));
        int realms = segments.indexOf("realms");
        if (realms >= 0 && segments.size() > realms + 4) {
            lock(segments.get(realms + 3), segments.get(realms + 4));
        }
    }

    @Override
    public void lock(String collection, String id) {
        RowLocks.lock(entityManager, realm.getId(), collection, id);
    }

    @Override
    public void flush() {
        // A long job ends the transaction of the request first, and writes in transactions of its own.
        if (writing && session.getTransactionManager().isActive()) {
            RowLocks.flush(entityManager);
        }
    }

    @Override
    public String requestQuery() {
        return session.getContext().getUri().getRequestUri().getRawQuery();
    }

    @Override
    public IdempotencyStore idempotency() {
        return new JpaIdempotencyStore(entityManager, realm.getId());
    }

    @Override
    public Locale locale() {
        return session.getContext().getHttpRequest().getHttpHeaders().getAcceptableLanguages().stream()
                .filter(locale -> !"*".equals(locale.getLanguage()))
                .findFirst()
                .orElse(Locale.ENGLISH);
    }

    @Override
    public String callerUserId() {
        return auth.adminAuth().getUser().getId();
    }

    @Override
    public RuntimeException failure(Response response) {
        return new ErrorResponseException(response);
    }

    @Override
    public void recordChange(Change change, String resourceType, String resourceId, String resourcePath,
            Object representation) {
        adminEvent.operation(OperationType.valueOf(change.name()))
                .resource(resourceType)
                .resourcePath(resourcePath)
                .representation(representation)
                .success();
        new JpaChangeLogStore(entityManager, realm.getId()).append(new ChangeLogEntry(UUID.randomUUID().toString(),
                0, Instant.now().truncatedTo(ChronoUnit.MILLIS), callerUserId(), resourceType,
                resourceId, change.name(), resourcePath, changeOrderId()));
    }

    @Override
    public String nextPage(String cursor) {
        return session.getContext().getUri().getRequestUriBuilder()
                .replaceQueryParam("first")
                .replaceQueryParam("after", cursor)
                .build()
                .toString();
    }

    private String changeOrderId() {
        return session.getContext().getUri().getQueryParameters().getFirst(CHANGE_ORDER_PARAMETER);
    }
}
