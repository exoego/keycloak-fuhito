package net.exoego.keycloak.fuhito.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefItemPeriod;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.service.RealmExport.AssignmentRows;
import net.exoego.keycloak.fuhito.service.RealmExport.EmploymentRow;
import net.exoego.keycloak.fuhito.service.RealmExport.PersonRow;
import net.exoego.keycloak.fuhito.service.RealmExport.QualificationRow;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.AttributeDefinitionStore;
import net.exoego.keycloak.fuhito.store.ChangeOrderStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
import net.exoego.keycloak.fuhito.store.HierarchyStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.QualificationStore;
import net.exoego.keycloak.fuhito.store.RealmExportStore;
import net.exoego.keycloak.fuhito.store.RealmKindStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;
import net.exoego.keycloak.fuhito.store.UnitRoleStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Takes every stored row of one realm out page by page, and loads such rows into a realm with no data, or in place
 * of the data of a realm.
 */
public class RealmExportService {

    /**
     * The stores that an export reads and a restore writes.
     *
     * @param export the reads of every row and the writes that the other stores do not have
     * @param refKinds the kinds of reference data of the realm
     * @param refItems the reference items
     * @param attributeDefinitions the definitions of free attributes
     * @param hierarchies the hierarchies
     * @param orgUnits the org units
     * @param people the people
     * @param employments the employments
     * @param assignments the assignments
     * @param reportingLines the reporting lines
     * @param unitRoles the roles over org units
     * @param qualifications the qualifications
     * @param externalIds the IDs that systems have for the resources
     * @param changeOrders the change orders
     * @param users the users of the realm
     * @param organizations the Keycloak organizations of the realm
     */
    public record Stores(RealmExportStore export, RealmKindStore refKinds, RefItemStore refItems,
            AttributeDefinitionStore attributeDefinitions, HierarchyStore hierarchies, OrgUnitStore orgUnits,
            PersonStore people, EmploymentStore employments, AssignmentStore assignments,
            ReportingLineStore reportingLines, UnitRoleStore unitRoles, QualificationStore qualifications,
            ExternalIdStore externalIds, ChangeOrderStore changeOrders, UserDirectory users,
            OrganizationDirectory organizations) {
    }

    /**
     * One page of one list of an export.
     *
     * @param rows the rows
     * @param lastId the ID of the last row, after which the next page starts, or null for an empty page
     */
    public record ExportPage(List<?> rows, String lastId) {
    }

    private final Stores stores;

    /**
     * Creates the service.
     *
     * @param stores the stores of the realm
     */
    public RealmExportService(Stores stores) {
        this.stores = stores;
    }

    /**
     * Reads one page of one list.
     *
     * @param list the name of the list, one of {@link RealmExport#LISTS}
     * @param afterId the ID after which the page starts, or null for the start
     * @param max the largest number of rows
     * @return the page
     * @throws FuhitoException when the list is unknown
     */
    public ExportPage page(String list, String afterId, int max) {
        RealmExportStore export = stores.export();
        return switch (Checks.required("list", list)) {
            case "ref-kinds" -> page(export.refKinds().read(afterId, max), RealmKind::id,
                    kind -> new RealmExport.Kind(kind.id(), kind.version(), kind.code(),
                            kind.names().primaryLanguage(), kind.names().byLanguage()));
            case "ref-items" -> refItems(export.refItems().read(afterId, max));
            case "attribute-definitions" -> page(export.attributeDefinitions().read(afterId, max),
                    AttributeDefinition::id, d -> new RealmExport.Definition(d.id(), d.version(), d.target().name(),
                            d.name(), d.type().name(), d.refKind(), d.multiple(), d.required()));
            case "hierarchies" -> page(export.hierarchies().read(afterId, max), Hierarchy::id,
                    h -> new RealmExport.HierarchyRow(h.id(), h.version(), h.code(), h.name()));
            case "org-units" -> orgUnits(export.orgUnits().read(afterId, max));
            case "people" -> people(export.people().read(afterId, max));
            case "employments" -> employments(export.employments().read(afterId, max));
            case "assignments" -> assignments(export.assignmentIds().read(afterId, max));
            case "reporting-lines" -> page(export.reportingLines().read(afterId, max), ReportingLine::id,
                    l -> new RealmExport.Line(l.id(), l.version(), l.assignmentId(), l.managerPersonId(),
                            l.kind().name(), from(l.period()), to(l.period())));
            case "unit-roles" -> page(export.unitRoles().read(afterId, max), UnitRole::id,
                    r -> new RealmExport.Role(r.id(), r.version(), r.employmentId(), r.orgUnitId(), r.roleId(),
                            r.includesDescendants(), from(r.period()), to(r.period())));
            case "qualifications" -> page(export.qualifications().read(afterId, max), Qualification::id,
                    q -> new QualificationRow(q.id(), q.version(), q.personId(), q.kindId(), q.number(),
                            from(q.period()), to(q.period()), q.attributes(), q.source()));
            case "external-ids" -> page(export.externalIds().read(afterId, max), ExternalId::id,
                    x -> new RealmExport.ExternalIdRow(x.id(), x.version(), x.kind().name(), x.resourceId(),
                            x.source(), x.externalId()));
            case "change-orders" -> page(export.changeOrders().read(afterId, max), ChangeOrder::id, this::order);
            case "erasures" -> page(export.erasures().read(afterId, max), RealmExportStore.Erasure::personId,
                    e -> new RealmExport.Erasure(e.personId(), e.erasedAt().toString()));
            default -> throw FuhitoException.notFound("export.list_unknown", Map.of("list", list));
        };
    }

    private static <T, R> ExportPage page(List<T> found, Function<T, String> idOf, Function<T, R> toRow) {
        return new ExportPage(found.stream().map(toRow).toList(), found.isEmpty() ? null : idOf.apply(found.getLast()));
    }

    private ExportPage refItems(List<RefItem> items) {
        if (items.isEmpty()) {
            return new ExportPage(List.of(), null);
        }
        Map<String, List<RealmExport.ItemNames>> names = stores.export()
                .namesOf(items.stream().map(RefItem::id).toList()).stream()
                .collect(Collectors.groupingBy(RefItemPeriod::refItemId, Collectors.mapping(
                        n -> new RealmExport.ItemNames(n.id(), text(n.validFrom()), text(n.validTo()),
                                n.names().primaryLanguage(), n.names().byLanguage()),
                        Collectors.toList())));
        return new ExportPage(items.stream()
                .map(i -> new RealmExport.Item(i.id(), i.version(), i.kind(), i.code(), i.displayOrder(),
                        i.attributes(), i.active(), i.legalEntity(), i.actingHead(), i.lateral(), i.rollup(),
                        names.get(i.id())))
                .toList(), items.getLast().id());
    }

    private ExportPage orgUnits(List<OrgUnit> units) {
        List<String> ids = units.stream().map(OrgUnit::id).toList();
        if (ids.isEmpty()) {
            return new ExportPage(List.of(), null);
        }
        Map<String, String> codes = stores.orgUnits().hierarchyCodes();
        Map<String, List<OrgUnitVersion>> versions = stores.export().versionsOf(ids).stream()
                .collect(Collectors.groupingBy(OrgUnitVersion::orgUnitId));
        Map<String, List<OrgUnitLink>> links = stores.export().parentLinksOf(ids).stream()
                .collect(Collectors.groupingBy(OrgUnitLink::childId));
        Map<String, List<OrgUnitSuccession>> successions = stores.export().successionsOf(ids).stream()
                .collect(Collectors.groupingBy(OrgUnitSuccession::predecessorId));
        return page(units, OrgUnit::id, unit -> new RealmExport.Unit(unit.id(), unit.version(), unit.source(),
                unit.timeZone(), unit.dayStart(),
                versions.getOrDefault(unit.id(), List.of()).stream()
                        .map(v -> new RealmExport.Version(v.id(), from(v.period()), to(v.period()), v.code(),
                                v.names().primaryLanguage(), v.names().byLanguage(), v.unitTypeId(),
                                v.workLocationId(), v.costCenterId(), v.displayOrder(), v.attributes()))
                        .toList(),
                // The ID of the default hierarchy differs between realms, so the code gives the order.
                links.getOrDefault(unit.id(), List.of()).stream()
                        .map(l -> new RealmExport.Parent(l.id(), codes.get(l.hierarchyId()), l.parentId(),
                                from(l.period()), to(l.period())))
                        .sorted(Comparator.comparing(RealmExport.Parent::hierarchy)
                                .thenComparing(RealmExport.Parent::validFrom))
                        .toList(),
                successions.getOrDefault(unit.id(), List.of()).stream()
                        .map(s -> new RealmExport.Succession(s.id(), s.successorId(), s.effectiveDate().toString()))
                        .toList(),
                Optional.ofNullable(unit.keycloakOrganizationId()).flatMap(stores.organizations()::aliasOf)
                        .orElse(null)));
    }

    private ExportPage people(List<Person> found) {
        Map<String, Instant> masks = found.isEmpty() ? Map.of()
                : stores.export().maskedAt(found.stream().map(Person::id).toList());
        return page(found, Person::id, p -> new PersonRow(p.id(), p.version(), username(p.userId()),
                p.names() == null ? null : p.names().primaryLanguage(),
                p.names() == null ? null : p.names().byLanguage(), p.attributes(), p.source(),
                Optional.ofNullable(masks.get(p.id())).map(Instant::toString).orElse(null)));
    }

    private ExportPage employments(List<Employment> found) {
        Map<String, List<EmploymentTerms>> terms = found.isEmpty() ? Map.of()
                : stores.export().termsOf(found.stream().map(Employment::id).toList()).stream()
                        .collect(Collectors.groupingBy(EmploymentTerms::employmentId));
        return page(found, Employment::id, e -> new EmploymentRow(e.id(), e.version(), e.personId(),
                e.employer().legalEntityId(), e.employer().externalEmployerId(),
                from(e.period()), to(e.period()), e.staffNumber(), e.endReasonId(), e.source(),
                e.continuesEmploymentId(), terms.getOrDefault(e.id(), List.of()).stream()
                        .map(t -> new RealmExport.Terms(t.id(), from(t.period()), to(t.period()),
                                t.employmentTypeId(), t.gradeId(), t.status().name(), t.leaveReasonId(),
                                t.workPercent(), Objects.toString(t.contractEndDate(), null), t.attributes()))
                        .toList()));
    }

    private ExportPage assignments(List<String> ids) {
        Map<String, List<Assignment>> rows = ids.isEmpty() ? Map.of()
                : stores.export().rowsOf(ids).stream().collect(Collectors.groupingBy(Assignment::assignmentId));
        // The rows of one assignment share its version.
        return page(ids, Function.identity(), id -> new AssignmentRows(id, rows.get(id).getFirst().version(),
                rows.get(id).stream()
                        .map(a -> new RealmExport.Period(a.rowId(), a.employmentId(), a.orgUnitId(),
                                from(a.period()), to(a.period()), a.jobTitleId(), a.occupationId(), a.kindId(),
                                a.workLocationId(), a.costCenterId(), a.head(), a.primary(), a.allocationPercent(),
                                a.displayOrder(), a.attributes(), a.source(), a.continuesAssignmentId()))
                        .toList()));
    }

    private RealmExport.Order order(ChangeOrder o) {
        return new RealmExport.Order(o.id(), o.version(), o.number(), o.kind(), o.title(), o.note(),
                text(o.orderDate()), text(o.effectiveDate()), username(o.approverUserId()),
                username(o.requesterUserId()), o.externalReference(), text(o.createdAt()), text(o.announcedAt()),
                usernameInRealm(o.announcedByUserId()));
    }

    // A user that Keycloak no longer has gives no username. The restored record then has no link.
    private String username(String userId) {
        return userId == null ? null : stores.users().username(userId).orElse(null);
    }

    // An admin of another realm, such as master, can act in this realm. Some Keycloak versions still find that
    // admin by ID, but a restore could not find the username here.
    private String usernameInRealm(String userId) {
        String username = username(userId);
        return username != null && stores.users().findIdByUsername(username).filter(userId::equals).isPresent()
                ? username : null;
    }

    private static String from(ValidPeriod period) {
        return period.from().toString();
    }

    private static String to(ValidPeriod period) {
        return text(period.to());
    }

    private static String text(Object value) {
        return Objects.toString(value, null);
    }

    /**
     * Loads every list of an export into the realm, with the IDs of the export, all or nothing.
     *
     * @param document the export
     * @param replace true to delete the data of the realm first
     * @param linking checks each Keycloak organization that a unit is linked to, or was linked to before a replace,
     *        before anything is written
     * @return the number of rows of each list
     * @throws FuhitoException when the format is unknown, the realm has data and is not replaced, a value is empty or
     *         too long for its column, a username has no user, or the realm has no organization with an alias
     */
    public Map<String, Integer> restore(RealmExport document, boolean replace, Consumer<String> linking) {
        Checks.required("body", document);
        if (!RealmExport.FORMAT.equals(document.format())) {
            throw FuhitoException.invalid("restore.format", Map.of("format", String.valueOf(document.format())));
        }
        if (!replace && !stores.export().isEmpty()) {
            throw FuhitoException.conflict("restore.realm_not_empty", Map.of());
        }
        RestoreCheck.requireValues(document, stores.export()::lengths);
        RestoreCheck.requireReferences(document);
        Map<String, String> organizations = organizations(document);
        if (replace) {
            stores.export().linkedOrganizations().stream()
                    .filter(id -> !organizations.containsValue(id))
                    .forEach(linking);
        }
        organizations.values().forEach(linking);
        if (replace) {
            stores.export().deleteData();
        }
        // A copy into a second realm of the same database names the rows of the first one by their IDs.
        for (Map.Entry<String, Collection<String>> list : RestoreCheck.ids(document).entrySet()) {
            Optional<String> taken = stores.export().takenId(list.getKey(), list.getValue());
            if (taken.isPresent()) {
                throw FuhitoException.conflict("restore.id_taken", Map.of("list", list.getKey(), "id", taken.get()));
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ref-kinds", each("ref-kinds", document.refKinds(), k -> stores.refKinds().insert(
                new RealmKind(k.id(), k.code(), RefKindName.check(k.primaryLanguage(), k.names()),
                        version(k.version())))));
        counts.put("ref-items", each("ref-items", document.refItems(), this::restoreRefItem));
        counts.put("attribute-definitions", each("attribute-definitions", document.attributeDefinitions(), d -> stores.attributeDefinitions()
                .insert(new AttributeDefinition(d.id(), AttributeDefinition.Target.valueOf(d.target()), d.name(),
                        AttributeDefinition.Type.valueOf(d.type()), d.refKind(), d.multiple(), d.required(),
                        version(d.version())))));
        Map<String, String> hierarchyIds = new HashMap<>();
        // A replace deleted the default hierarchy, and this makes it again.
        hierarchyIds.put(Hierarchy.DEFAULT_CODE, stores.hierarchies().defaultId());
        counts.put("hierarchies", each("hierarchies", document.hierarchies(), h -> {
            // The default hierarchy of the empty realm has no links yet. The one of the export takes its place,
            // with its ID and its name. An export from before this change has none.
            if (h.code().equals(Hierarchy.DEFAULT_CODE)) {
                stores.hierarchies().delete(hierarchyIds.get(Hierarchy.DEFAULT_CODE));
            }
            stores.hierarchies().insert(new Hierarchy(h.id(), h.code(), h.name(), version(h.version())));
            hierarchyIds.put(h.code(), h.id());
        }));
        rows("org-units", () -> restoreOrgUnits(Optional.ofNullable(document.orgUnits()).orElse(List.of()),
                hierarchyIds, organizations, counts));
        counts.put("people", each("people", document.people(), p -> {
            stores.people().insert(new Person(p.id(), userId(p.username()),
                    PersonName.check(p.primaryLanguage(), p.names()), p.attributes(), p.source(),
                    version(p.version())));
            if (p.maskedAt() != null) {
                stores.export().restoreMask(p.id(), Instant.parse(p.maskedAt()));
            }
        }));
        rows("employments", () -> restoreEmployments(Optional.ofNullable(document.employments())
                .orElse(List.of()), counts));
        rows("assignments", () -> restoreAssignments(Optional.ofNullable(document.assignments())
                .orElse(List.of()), counts));
        counts.put("reporting-lines", each("reporting-lines", document.reportingLines(), l -> stores.reportingLines().insert(
                new ReportingLine(l.id(), l.assignmentId(), l.managerPersonId(), null,
                        ReportingLine.Kind.valueOf(l.kind()), period(l.validFrom(), l.validTo()),
                        version(l.version())))));
        counts.put("unit-roles", each("unit-roles", document.unitRoles(), r -> stores.unitRoles().insert(new UnitRole(r.id(),
                r.employmentId(), null, null, r.orgUnitId(), r.roleId(), r.includesDescendants(),
                period(r.validFrom(), r.validTo()), version(r.version())))));
        counts.put("qualifications", each("qualifications", document.qualifications(), q -> stores.qualifications().insert(
                new Qualification(q.id(), q.personId(), null, q.kindId(), q.number(), period(q.validFrom(),
                        q.validTo()), q.attributes(), q.source(), version(q.version())))));
        counts.put("change-orders", each("change-orders", document.changeOrders(), o -> stores.changeOrders().insert(new ChangeOrder(
                o.id(), o.number(), o.kind(), o.title(), o.note(), date(o.orderDate()), date(o.effectiveDate()),
                userId(o.approver()), userId(o.requester()), o.externalReference(), instant(o.createdAt()),
                instant(o.announcedAt()), userId(o.announcedBy()), version(o.version())))));
        // An ID of another system needs the resource that has it.
        counts.put("external-ids", each("external-ids", document.externalIds(), x -> stores.externalIds().insert(new ExternalId(
                x.id(), ExternalId.Kind.valueOf(x.resourceType()), x.resourceId(), x.source(), x.externalId(),
                version(x.version())))));
        counts.put("erasures", each("erasures", document.erasures(), e -> stores.export().restoreErasure(
                new RealmExportStore.Erasure(Objects.requireNonNull(e.personId()), Instant.parse(e.erasedAt())))));
        stores.export().eraseAgain();
        return counts;
    }

    // The IDs of the organizations by alias. One organization has one org unit.
    private Map<String, String> organizations(RealmExport document) {
        Map<String, String> ids = new HashMap<>();
        Map<String, String> unitOfAlias = new HashMap<>();
        for (RealmExport.Unit unit : Optional.ofNullable(document.orgUnits()).orElse(List.of())) {
            String alias = unit == null ? null : unit.organizationAlias();
            if (alias == null) {
                continue;
            }
            String id = ids.computeIfAbsent(alias, a -> stores.organizations().idOfAlias(a).orElseThrow(
                    () -> FuhitoException.invalid("restore.organization_unknown", Map.of("alias", a))));
            String other = unitOfAlias.putIfAbsent(alias, unit.id());
            if (other != null) {
                throw FuhitoException.conflict("organization.linked",
                        Map.of("organizationId", id, "orgUnitId", other));
            }
        }
        return ids;
    }

    private void restoreRefItem(RealmExport.Item i) {
        RefItem item = new RefItem(i.id(), i.kind(), i.code(), null, i.displayOrder(), i.attributes(), i.active(),
                i.legalEntity(), i.actingHead(), i.lateral(), i.rollup(), version(i.version()), null, null);
        stores.refItems().insert(item, Checks.required("versions", i.versions()).stream()
                .map(n -> new RefItemPeriod(n.id(), i.id(), date(n.validFrom()), date(n.validTo()),
                        RefItemName.check(n.primaryLanguage(), n.names())))
                .toList());
    }

    // Every unit comes first, so that a parent or a successor exists when a link names it.
    private void restoreOrgUnits(List<RealmExport.Unit> units, Map<String, String> hierarchyIds,
            Map<String, String> organizations, Map<String, Integer> counts) {
        OrgUnitStore store = stores.orgUnits();
        units.forEach(u -> store.insertUnit(new OrgUnit(u.id(), u.source(),
                u.organizationAlias() == null ? null : organizations.get(u.organizationAlias()), u.timeZone(),
                u.dayStart(), version(u.version()))));
        for (RealmExport.Unit u : units) {
            u.versions().forEach(v -> store.insertVersion(new OrgUnitVersion(v.id(), u.id(), period(v.validFrom(),
                    v.validTo()), v.code(), Localized.check(v.primaryLanguage(), v.names(),
                    (field, name) -> name.checked(field)), v.unitTypeId(),
                    v.workLocationId(), v.costCenterId(), v.displayOrder(), v.attributes())));
        }
        for (RealmExport.Unit u : units) {
            for (RealmExport.Parent p : u.parents()) {
                String hierarchyId = Optional.ofNullable(hierarchyIds.get(p.hierarchy())).orElseThrow(
                        () -> FuhitoException.invalid("restore.hierarchy_unknown", Map.of("code", p.hierarchy())));
                store.insertLink(new OrgUnitLink(p.id(), hierarchyId, u.id(), p.parentId(),
                        period(p.validFrom(), p.validTo())));
            }
            u.successors().forEach(s -> store.insertSuccession(new OrgUnitSuccession(s.id(), u.id(),
                    s.successorId(), LocalDate.parse(s.effectiveDate()))));
        }
        counts.put("org-units", units.size());
    }

    // An employment that continues another comes after it.
    private void restoreEmployments(List<EmploymentRow> rows, Map<String, Integer> counts) {
        List<EmploymentRow> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing((EmploymentRow e) -> e.continuesEmploymentId() != null)
                .thenComparing(EmploymentRow::validFrom));
        for (EmploymentRow e : ordered) {
            stores.employments().insert(new Employment(e.id(), e.personId(), null,
                    new Employer(e.employerId(), e.externalEmployerId()),
                    period(e.validFrom(), e.validTo()), e.staffNumber(), e.endReasonId(), e.source(),
                    e.continuesEmploymentId(), version(e.version())));
            e.terms().forEach(t -> stores.employments().insertTerms(new EmploymentTerms(t.id(), e.id(),
                    period(t.validFrom(), t.validTo()), t.employmentTypeId(), t.gradeId(),
                    EmploymentStatus.valueOf(t.status()), t.leaveReasonId(), t.workPercent(),
                    date(t.contractEndDate()), t.attributes())));
        }
        counts.put("employments", rows.size());
    }

    // A row that continues another assignment comes after it.
    private void restoreAssignments(List<AssignmentRows> assignments, Map<String, Integer> counts) {
        List<RealmExport.Period> rows = new ArrayList<>(assignments.stream()
                .flatMap(a -> a.periods().stream()).toList());
        rows.sort(Comparator.comparing((RealmExport.Period r) -> r.continuesAssignmentId() != null)
                .thenComparing(RealmExport.Period::validFrom));
        Map<String, AssignmentRows> assignmentOfRow = new HashMap<>();
        assignments.forEach(a -> a.periods().forEach(r -> assignmentOfRow.put(r.rowId(), a)));
        rows.forEach(r -> stores.assignments().insert(new Assignment(r.rowId(),
                assignmentOfRow.get(r.rowId()).assignmentId(), r.employmentId(), r.orgUnitId(),
                period(r.validFrom(), r.validTo()), r.jobTitleId(), r.occupationId(), r.kindId(), r.workLocationId(),
                r.costCenterId(), r.head(), r.primary(), r.allocationPercent(), r.displayOrder(), r.attributes(),
                r.source(), r.continuesAssignmentId(), version(assignmentOfRow.get(r.rowId()).version()))));
        counts.put("assignments", assignments.size());
    }

    private static <T> int each(String list, List<T> rows, Consumer<T> write) {
        List<T> all = rows == null ? List.of() : rows;
        rows(list, () -> all.forEach(write));
        return all.size();
    }

    // A value that is missing, or that cannot be read, is an error of the export. The restore then writes nothing.
    private static void rows(String list, Runnable write) {
        try {
            write.run();
        } catch (NullPointerException | IllegalArgumentException | DateTimeException e) {
            throw FuhitoException.invalid("restore.bad_value", Map.of("list", list)).causedBy(e);
        }
    }

    private String userId(String username) {
        if (username == null) {
            return null;
        }
        return stores.users().findIdByUsername(username).orElseThrow(() -> FuhitoException.invalid(
                "restore.username_unknown", Map.of("username", username)));
    }

    // An export from before versions has none.
    private static long version(Long version) {
        return version == null ? 1 : version;
    }

    private static ValidPeriod period(String from, String to) {
        return new ValidPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
    }

    private static LocalDate date(String text) {
        return text == null ? null : LocalDate.parse(text);
    }

    private static Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
