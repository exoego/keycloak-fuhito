package net.exoego.keycloak.fuhito.keycloak;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Messages;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.jpa.JpaChangeLogStore;
import net.exoego.keycloak.fuhito.jpa.JpaChangeOrderStore;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
import net.exoego.keycloak.fuhito.rest.ErrorRepresentation;
import net.exoego.keycloak.fuhito.rest.RequestContext;
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

    private static final String LANGUAGE_ATTRIBUTE = "fuhito.language";

    private static final Pattern OWS_AROUND = Pattern.compile("^[ \\t]+|[ \\t]+$");

    // An element of Accept-Language by RFC 9110 sections 12.5.4 and 12.4.2, with a basic language range of RFC 4647.
    private static final Pattern LANGUAGE_RANGE = Pattern.compile("([A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*|\\*)"
            + "(?:[ \\t]*;[ \\t]*[Qq]=(0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?))?");

    private final KeycloakSession session;
    private final RealmModel realm;
    private final AdminPermissionEvaluator auth;
    private final AdminEventBuilder adminEvent;
    private final EntityManager entityManager;
    private final FuhitoException timeSettingsError;
    private boolean writing;
    private boolean realmLocked;

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
        return error(status, code, params, locale());
    }

    static Response error(Response.Status status, String code, Map<String, String> params, Locale locale) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorRepresentation(code, params, Messages.format(code, params, locale)))
                .build();
    }

    @Override
    public void requireWrite() {
        requireWrite(false);
    }

    @Override
    public void requireBulkWrite() {
        requireWrite(true);
    }

    @Override
    public void requireWrite(BooleanSupplier changesOtherReads) {
        requireRole(FuhitoRoles.MANAGE);
        requireTimeSettings();
        requireWrite(changesOtherReads.getAsBoolean());
    }

    @Override
    public String requireWriteOfUser(Supplier<String> userId) {
        requireRole(FuhitoRoles.MANAGE);
        requireTimeSettings();
        String id = userId.get();
        if (id != null) {
            RowLocks.lockUser(entityManager, realm.getId(), id);
        }
        requireWrite(false);
        return id;
    }

    private void requireWrite(boolean alone) {
        requireRole(FuhitoRoles.MANAGE);
        writing = true;
        requireTimeSettings();
        // Before the check, so that it finds a change order that a bulk write added.
        lockRealm(alone);
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
    public void lockTarget(Map<String, ? extends Collection<String>> others) {
        Map<String, Set<String>> all = new HashMap<>();
        others.forEach((collection, ids) -> all.computeIfAbsent(collection, any -> new HashSet<>()).addAll(ids));
        List<String> parts = pathParts();
        if (parts.size() > 1 && RowLocks.knows(parts.getFirst())) {
            all.computeIfAbsent(parts.getFirst(), any -> new HashSet<>()).add(parts.get(1));
        }
        lock(all);
    }

    @Override
    public void lock(Map<String, ? extends Collection<String>> ids) {
        lockRealm(false);
        RowLocks.lock(entityManager, realm.getId(), ids);
    }

    // The row of the realm comes before every other lock but the row of a user, also for a write that did not ask
    // for it.
    private void lockRealm(boolean alone) {
        if (!realmLocked) {
            try {
                RowLocks.lockRealm(entityManager, realm.getId(), alone);
            } catch (FuhitoException e) {
                throw failure(error(Response.Status.NOT_FOUND, e.code(), e.params()));
            }
            realmLocked = true;
        }
    }

    @Override
    public void flush() {
        // A long job ends the transaction of the request first, and writes in transactions of its own.
        if (writing && session.getTransactionManager().isActive()) {
            RowLocks.flush(entityManager);
        }
    }

    @Override
    public Locale locale() {
        return locale(session);
    }

    @Override
    public String nameLanguage() {
        return language(session).tag();
    }

    static Locale locale(KeycloakSession session) {
        String tag = language(session).tag();
        return tag == null ? Locale.ENGLISH : Locale.forLanguageTag(tag);
    }

    /**
     * Checks {@code Accept-Language} of a request of the API, and keeps the language that it gives for the rest of
     * the request.
     *
     * @param session the session of the request
     * @throws ErrorResponseException 400 with {@code invalid.header} and a message in English, when the header is not
     *         in the form of RFC 9110
     */
    static void checkLanguage(KeycloakSession session) {
        String header = session.getContext().getHttpRequest().getHttpHeaders()
                .getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        try {
            session.setAttribute(LANGUAGE_ATTRIBUTE, new Language(language(header)));
        } catch (FuhitoException e) {
            throw new ErrorResponseException(error(Response.Status.BAD_REQUEST, e.code(), e.params(), Locale.ENGLISH));
        }
    }

    private static Language language(KeycloakSession session) {
        return session.getAttribute(LANGUAGE_ATTRIBUTE, Language.class);
    }

    /**
     * Reads {@code Accept-Language} by RFC 9110 and picks the language of the request. It is the first tag of the
     * highest {@code q}, and never {@code *}. A tag with {@code q=0} is not acceptable, as RFC 9110 says, so it is
     * never picked. The tag stays as the header writes it, because {@link Locale} would turn {@code iw} into
     * {@code he}. An empty element of the list is ignored, as RFC 9110 asks.
     *
     * @param header the value of the header, or null
     * @return the tag, or null when the header names no language with a weight above 0
     * @throws FuhitoException with {@code invalid.header} when an element is not a basic language range of RFC 4647
     *         or {@code *}, with at most a {@code q} of RFC 9110
     */
    static String language(String header) {
        if (header == null) {
            return null;
        }
        String language = null;
        double highest = 0;
        for (String element : header.split(",", -1)) {
            String range = OWS_AROUND.matcher(element).replaceAll("");
            if (range.isEmpty()) {
                continue;
            }
            Matcher parts = LANGUAGE_RANGE.matcher(range);
            if (!parts.matches()) {
                throw FuhitoException.invalid("invalid.header",
                        Map.of("field", HttpHeaders.ACCEPT_LANGUAGE, "value", range));
            }
            double weight = parts.group(2) == null ? 1 : Double.parseDouble(parts.group(2));
            if (!parts.group(1).equals("*") && weight > highest) {
                language = parts.group(1);
                highest = weight;
            }
        }
        return language;
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
    public void recordChanges(List<ChangeRecord> changes) {
        String actorUserId = callerUserId();
        String changeOrderId = changeOrderId();
        List<ChangeLogEntry> entries = new ArrayList<>();
        for (ChangeRecord change : changes) {
            adminEvent.operation(OperationType.valueOf(change.change().name()))
                    .resource(change.resourceType().name())
                    .resourcePath(change.resourcePath())
                    .representation(change.representation())
                    .success();
            entries.add(ChangeLogEntry.write(UUID.randomUUID().toString(),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS), actorUserId, change.resourceType(),
                    change.resourceId(), change.change().name(), change.resourcePath(), changeOrderId));
        }
        JpaChangeLogStore log = new JpaChangeLogStore(entityManager, realm.getId());
        List<ChangeLogEntry> writes = log.appendAll(entries);
        // The entries of the writes need their numbers first, so that each propagated change can name its cause.
        Map<List<Object>, ChangeLogEntry> propagated = new LinkedHashMap<>();
        Set<List<Object>> written = new HashSet<>();
        changes.forEach(change -> written.add(List.of(change.resourceType(), change.resourceId())));
        for (int i = 0; i < changes.size(); i++) {
            ChangeLogEntry write = writes.get(i);
            for (PropagatedChange read : changes.get(i).propagated()) {
                List<Object> resource = List.of(read.value().resourceType(), read.resourceId());
                if (!written.contains(resource)) {
                    propagated.computeIfAbsent(resource, any -> write.causing(UUID.randomUUID().toString(), read));
                }
            }
        }
        if (!propagated.isEmpty()) {
            log.appendAll(List.copyOf(propagated.values()));
        }
    }

    @Override
    public List<String> pathParts() {
        // The path is /admin/realms/{realm}/fuhito/...
        List<String> segments = List.of(session.getContext().getUri().getPath().split("/"));
        int start = Math.min(segments.indexOf("realms") + 3, segments.size());
        return segments.subList(start, segments.size());
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

    /**
     * The language that the checked {@code Accept-Language} of a request gives.
     *
     * @param tag the tag as the header writes it, or null for none
     */
    private record Language(String tag) {
    }
}
