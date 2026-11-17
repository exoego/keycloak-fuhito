package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;

/**
 * Writes a list of the realm on a date as a CSV that the CSV import loads again with no change.
 */
public class CsvExportService {

    // The mark that tells a spreadsheet that the file is UTF-8.
    private static final String BYTE_ORDER_MARK = "﻿";
    private static final int ROWS_AT_A_TIME = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> LISTS = List.of("ref-kinds", "attribute-definitions", "ref-items",
            "hierarchies", "org-units", "org-unit-parents", "people", "employments", "assignments",
            "reporting-lines", "unit-roles", "qualifications", "external-ids", "change-orders");

    private final RefItemService refItems;
    private final Supplier<List<RealmKindService.View>> kinds;
    private final OrgUnitService orgUnits;
    private final PersonService people;
    private final EmploymentService employments;
    private final AssignmentService assignments;
    private final Today today;
    private final HierarchyService hierarchies;
    private final OrganizationDirectory organizations;
    private final ReportingLineService reportingLines;
    private final UnitRoleService unitRoles;
    private final QualificationService qualifications;
    private final ExternalIdService externalIds;
    private final ChangeOrderService changeOrders;
    private final Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions;

    /**
     * Creates the service.
     *
     * @param refItems the operations on reference items of the realm
     * @param kinds the kinds of reference items, the fixed ones and the ones of the realm
     * @param orgUnits the operations on org units of the realm
     * @param people the operations on people of the realm
     * @param employments the operations on employments of the realm
     * @param assignments the operations on assignments of the realm
     * @param today what today is for the realm
     * @param hierarchies the operations on the hierarchies of the realm
     * @param organizations the Keycloak organizations of the realm
     * @param reportingLines the operations on the reporting lines of the realm
     * @param unitRoles the operations on the roles over org units of the realm
     * @param qualifications the operations on the qualifications of the realm
     * @param externalIds the operations on the IDs of other systems of the realm
     * @param changeOrders the operations on the change orders of the realm
     * @param definitions the definitions of free attributes of the realm, by the resource that has them
     */
    public CsvExportService(RefItemService refItems, Supplier<List<RealmKindService.View>> kinds,
            OrgUnitService orgUnits,
            PersonService people, EmploymentService employments, AssignmentService assignments, Today today,
            HierarchyService hierarchies, OrganizationDirectory organizations, ReportingLineService reportingLines,
            UnitRoleService unitRoles, QualificationService qualifications, ExternalIdService externalIds,
            ChangeOrderService changeOrders, Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions) {
        this.changeOrders = changeOrders;
        this.externalIds = externalIds;
        this.unitRoles = unitRoles;
        this.qualifications = qualifications;
        this.reportingLines = reportingLines;
        this.hierarchies = hierarchies;
        this.organizations = organizations;
        this.refItems = refItems;
        this.kinds = kinds;
        this.orgUnits = orgUnits;
        this.people = people;
        this.employments = employments;
        this.assignments = assignments;
        this.today = today;
        this.definitions = definitions;
    }

    /**
     * Writes a list on a date.
     *
     * @param list the list, as the path of the CSV import names it, for example {@code org-units}
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param japanese true for the Japanese names of the columns
     * @return the CSV
     * @throws FuhitoException when the list is unknown, or the date is bad
     */
    public String write(String list, String asOf, boolean japanese) {
        return switch (String.valueOf(list)) {
            case "ref-kinds" -> refKinds(japanese);
            case "attribute-definitions" -> attributeDefinitions(japanese);
            case "hierarchies" -> hierarchies(japanese);
            case "org-unit-parents" -> orgUnitParents(asOf, japanese);
            case "ref-items" -> refItems(japanese);
            case "org-units" -> orgUnits(asOf, japanese);
            case "people" -> people(japanese);
            case "employments" -> employments(asOf, japanese);
            case "assignments" -> assignments(asOf, japanese);
            case "reporting-lines" -> reportingLines(asOf, japanese);
            case "unit-roles" -> unitRoles(asOf, japanese);
            case "qualifications" -> qualifications(asOf, japanese);
            case "external-ids" -> externalIds(japanese);
            case "change-orders" -> changeOrders(japanese);
            default -> throw FuhitoException.invalid("csv.export_list_unknown", Map.of("list", String.valueOf(list),
                    "lists", String.join(", ", LISTS)));
        };
    }

    // Only the kinds that the realm added. A fixed kind is in every realm.
    private String refKinds(boolean japanese) {
        List<RealmKindService.View> added = kinds.get().stream().filter(kind -> !kind.fixed()).toList();
        SortedSet<String> languages = new TreeSet<>();
        added.forEach(kind -> languages.addAll(kind.names().keySet()));
        List<String> header = new ArrayList<>(List.of(column("code", japanese), column("name", japanese)));
        languages.forEach(language -> header.add(column("name", japanese) + ":" + language));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        for (RealmKindService.View kind : added) {
            List<String> cells = new ArrayList<>(List.of(kind.code(), kind.name()));
            languages.forEach(language -> cells.add(text(kind.names().get(language))));
            line(csv, cells);
        }
        return csv.toString();
    }

    private String hierarchies(boolean japanese) {
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, List.of(column("code", japanese), column("name", japanese)));
        for (int first = 0;; first += ROWS_AT_A_TIME) {
            List<Hierarchy> page = hierarchies.list(Page.of(first, ROWS_AT_A_TIME));
            page.forEach(hierarchy -> line(csv, List.of(hierarchy.code(), hierarchy.name())));
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
        }
    }

    // The parents of the default hierarchy are in the list of org units.
    private String orgUnitParents(String asOf, boolean japanese) {
        LocalDate date = asOf == null ? today.ofRealm() : Checks.date("asOf", asOf);
        List<Hierarchy> all = new ArrayList<>();
        for (int first = 0;; first += ROWS_AT_A_TIME) {
            List<Hierarchy> page = hierarchies.list(Page.of(first, ROWS_AT_A_TIME));
            all.addAll(page);
            if (page.size() < ROWS_AT_A_TIME) {
                break;
            }
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, List.of(column("hierarchy", japanese), column("orgUnitId", japanese), column("parentId", japanese)));
        for (Hierarchy hierarchy : all) {
            if (hierarchy.isDefault()) {
                continue;
            }
            orgUnits.parentLinks(hierarchy.code(), date).stream()
                    .sorted(Comparator.comparing(OrgUnitLink::childId))
                    .forEach(link -> line(csv, List.of(hierarchy.code(), link.childId(), link.parentId())));
        }
        return csv.toString();
    }

    private String attributeDefinitions(boolean japanese) {
        List<String> header = new ArrayList<>();
        for (String field : List.of("target", "name", "type", "refKind", "multiple", "required")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        for (AttributeDefinition.Target target : AttributeDefinition.Target.values()) {
            for (AttributeDefinition definition : definitions.apply(target)) {
                line(csv, List.of(target.name(), definition.name(), definition.type().name(),
                        text(definition.refKind()), Boolean.toString(definition.multiple()),
                        Boolean.toString(definition.required())));
            }
        }
        return csv.toString();
    }

    // A mark is left blank for a kind that cannot have it, as a blank cell means no mark for a new item.
    private String refItems(boolean japanese) {
        List<RefItem> items = new ArrayList<>();
        for (String kind : kinds.get().stream().map(RealmKindService.View::code).toList()) {
            for (int first = 0;; first += ROWS_AT_A_TIME) {
                List<RefItem> page = refItems.list(kind, true, Page.of(first, ROWS_AT_A_TIME));
                items.addAll(page);
                if (page.size() < ROWS_AT_A_TIME) {
                    break;
                }
            }
        }
        SortedSet<String> languages = new TreeSet<>();
        items.forEach(item -> languages.addAll(item.names().keySet()));
        List<String> attributes = definitions.apply(AttributeDefinition.Target.REF_ITEM).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> header = new ArrayList<>();
        for (String field : List.of("kind", "code", "name")) {
            header.add(column(field, japanese));
        }
        languages.forEach(language -> header.add(column("name", japanese) + ":" + language));
        for (String field : List.of("displayOrder", "active", "legalEntity", "actingHead", "lateral")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        for (RefItem item : items) {
            List<String> cells = new ArrayList<>(List.of(item.kind(), item.code(), item.name()));
            languages.forEach(language -> cells.add(text(item.names().get(language))));
            cells.add(Integer.toString(item.displayOrder()));
            cells.add(Boolean.toString(item.active()));
            boolean unitType = item.is(RefKind.UNIT_TYPE);
            cells.add(unitType ? Boolean.toString(item.legalEntity()) : "");
            cells.add(item.is(RefKind.ASSIGNMENT_KIND) ? Boolean.toString(item.actingHead()) : "");
            cells.add(unitType ? Boolean.toString(item.lateral()) : "");
            attributes.forEach(attribute -> cells.add(text(Optional.ofNullable(item.attributes())
                    .map(values -> values.get(attribute)).orElse(null))));
            line(csv, cells);
        }
        return csv.toString();
    }

    // The list of people can be large. It is read twice in pages, first for the languages of the names. The link
    // to a Keycloak user is left out: a blank cell keeps it, and the ID of a removed user would not load again.
    private String people(boolean japanese) {
        SortedSet<String> languages = new TreeSet<>();
        eachPerson(person -> languages.addAll(person.names().keySet()));
        List<String> attributes = definitions.apply(AttributeDefinition.Target.PERSON).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "name")) {
            header.add(column(field, japanese));
        }
        languages.forEach(language -> header.add(column("name", japanese) + ":" + language));
        header.add(column("phoneticName", japanese));
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        eachPerson(person -> {
            List<String> cells = new ArrayList<>(List.of(person.id(), text(person.externalId()),
                    text(person.source()), text(person.name())));
            languages.forEach(language -> cells.add(text(person.names().get(language))));
            cells.add(text(person.phoneticName()));
            attributes.forEach(attribute -> cells.add(text(Optional.ofNullable(person.attributes())
                    .map(values -> values.get(attribute)).orElse(null))));
            line(csv, cells);
        });
        return csv.toString();
    }

    // The employments that hold on the date, with the terms of that date. The person and the employer are named by
    // ID, as many have no external ID of the source of the row.
    private String employments(String asOf, boolean japanese) {
        LocalDate date = asOf == null ? today.ofRealm() : Checks.date("asOf", asOf);
        List<String> attributes = definitions.apply(AttributeDefinition.Target.EMPLOYMENT_TERMS).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "personId", "staffNumber", "employerId",
                "employmentType", "grade", "status", "leaveReason", "workPercent", "contractEndDate", "validFrom",
                "validTo", "endReason", "continuesEmploymentId")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<EmploymentView> page = employments.list(new EmploymentService.Search(null, null, date.toString()),
                    Page.of(0, ROWS_AT_A_TIME), after);
            for (EmploymentView view : page) {
                Employment employment = view.employment();
                Optional<EmploymentView.TermsView> terms = view.terms().stream()
                        .filter(t -> t.terms().period().contains(date))
                        .findFirst();
                List<String> cells = new ArrayList<>(List.of(employment.id(), text(employment.externalId()),
                        text(employment.source()), employment.personId(), text(employment.staffNumber()),
                        text(employment.employerId())));
                cells.add(text(terms.map(EmploymentView.TermsView::employmentType).orElse(null)));
                cells.add(text(terms.map(EmploymentView.TermsView::grade).orElse(null)));
                cells.add(text(terms.map(t -> t.terms().status().name()).orElse(null)));
                cells.add(text(terms.map(EmploymentView.TermsView::leaveReason).orElse(null)));
                cells.add(number(terms.map(t -> t.terms().workPercent()).orElse(null)));
                cells.add(text(terms.map(t -> t.terms().contractEndDate()).orElse(null)));
                cells.add(employment.period().from().toString());
                cells.add(text(employment.period().to()));
                cells.add(text(view.endReason()));
                cells.add(text(employment.continuesEmploymentId()));
                attributes.forEach(attribute -> cells.add(text(terms.map(t -> t.terms().attributes())
                        .map(values -> values.get(attribute)).orElse(null))));
                line(csv, cells);
            }
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = Cursor.encode(Cursor.EMPLOYMENTS, page.getLast().employment().id());
        }
    }

    // The assignments that hold on the date, with the details of that date and the dates of the whole assignment.
    private String assignments(String asOf, boolean japanese) {
        LocalDate date = asOf == null ? today.ofRealm() : Checks.date("asOf", asOf);
        List<String> attributes = definitions.apply(AttributeDefinition.Target.ASSIGNMENT).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "employmentId", "orgUnitId", "jobTitle",
                "occupation", "assignmentKind", "head", "primary", "allocationPercent", "displayOrder", "validFrom",
                "validTo", "continuesAssignmentId")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<AssignmentView> page = assignments.listOn(date, after, ROWS_AT_A_TIME);
            for (AssignmentView view : page) {
                AssignmentView.Row current = view.rows().stream()
                        .filter(r -> r.row().period().contains(date))
                        .findFirst()
                        .orElseThrow();
                Assignment row = current.row();
                List<String> cells = new ArrayList<>(List.of(view.assignmentId(), text(row.externalId()),
                        text(row.source()), row.employmentId(), row.orgUnitId(), text(current.jobTitle()),
                        text(current.occupation()), text(current.kind()), Boolean.toString(row.head()),
                        Boolean.toString(row.primary()), number(row.allocationPercent()),
                        Integer.toString(row.displayOrder()), view.period().from().toString(),
                        text(view.period().to()), text(view.continuesAssignmentId())));
                attributes.forEach(attribute -> cells.add(text(Optional.ofNullable(row.attributes())
                        .map(values -> values.get(attribute)).orElse(null))));
                line(csv, cells);
            }
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = page.getLast().assignmentId();
        }
    }

    private String reportingLines(String asOf, boolean japanese) {
        LocalDate date = asOf == null ? today.ofRealm() : Checks.date("asOf", asOf);
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "assignmentId", "managerPersonId", "kind", "validFrom", "validTo")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<ReportingLine> page = reportingLines.listOn(date, after, ROWS_AT_A_TIME);
            page.forEach(l -> line(csv, List.of(l.id(), l.assignmentId(), l.managerPersonId(), l.kind().name(),
                    l.period().from().toString(), text(l.period().to()))));
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = page.getLast().id();
        }
    }

    // Every order, newest effective date first. The announcement is a fact of its own, so it is not in the file.
    private String changeOrders(boolean japanese) {
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "number", "kind", "title", "note", "orderDate", "effectiveDate",
                "approverUserId", "requesterUserId", "externalReference")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        for (int first = 0;; first += ROWS_AT_A_TIME) {
            List<ChangeOrder> page = changeOrders.list(null, null, first, ROWS_AT_A_TIME);
            page.forEach(order -> line(csv, List.of(order.id(), text(order.number()), text(order.kind()),
                    order.title(), text(order.note()), text(order.orderDate()), order.effectiveDate().toString(),
                    text(order.approverUserId()), text(order.requesterUserId()), text(order.externalReference()))));
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
        }
    }

    // Only the IDs that /external-ids added. The own pair of a resource is in the list of the resource.
    private String externalIds(boolean japanese) {
        List<String> header = new ArrayList<>();
        for (String field : List.of("resourceType", "resourceId", "source", "externalId")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<ExternalIdService.View> page = externalIds.list(after, ROWS_AT_A_TIME);
            page.forEach(view -> line(csv, List.of(view.kind().name(), view.resourceId(), view.source(),
                    view.externalId())));
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = page.getLast().rowId();
        }
    }

    // A qualification has dates of its own, so the file has all of them, unless a date is given.
    private String qualifications(String asOf, boolean japanese) {
        List<String> attributes = definitions.apply(AttributeDefinition.Target.QUALIFICATION).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "personId", "kind", "number", "validFrom",
                "validTo")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<QualificationView> page = qualifications.list(null, asOf, Page.of(0, ROWS_AT_A_TIME), after);
            for (QualificationView view : page) {
                Qualification q = view.qualification();
                List<String> cells = new ArrayList<>(List.of(q.id(), text(q.externalId()), text(q.source()),
                        q.personId(), text(view.kind()), text(q.number()), q.period().from().toString(),
                        text(q.period().to())));
                attributes.forEach(attribute -> cells.add(text(Optional.ofNullable(q.attributes())
                        .map(values -> values.get(attribute)).orElse(null))));
                line(csv, cells);
            }
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = Cursor.encode(Cursor.QUALIFICATIONS, page.getLast().qualification().id());
        }
    }

    private String unitRoles(String asOf, boolean japanese) {
        LocalDate date = asOf == null ? today.ofRealm() : Checks.date("asOf", asOf);
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "employmentId", "orgUnitId", "role", "includesDescendants", "validFrom",
                "validTo")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<UnitRoleService.View> page = unitRoles.listOn(date, after, ROWS_AT_A_TIME);
            for (UnitRoleService.View view : page) {
                UnitRole role = view.role();
                line(csv, List.of(role.id(), role.employmentId(), role.orgUnitId(), text(view.code()),
                        Boolean.toString(role.includesDescendants()), role.period().from().toString(),
                        text(role.period().to())));
            }
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = page.getLast().role().id();
        }
    }

    private void eachPerson(Consumer<Person> each) {
        String after = null;
        while (true) {
            List<PersonView> page = people.list(null, null, ROWS_AT_A_TIME, after);
            page.forEach(view -> each.accept(view.person()));
            if (page.size() < ROWS_AT_A_TIME) {
                return;
            }
            after = Cursor.encode(Cursor.PEOPLE, page.getLast().person().id());
        }
    }

    private String orgUnits(String asOf, boolean japanese) {
        List<OrgUnitState> units = new ArrayList<>();
        for (int first = 0;; first += ROWS_AT_A_TIME) {
            List<OrgUnitState> page = orgUnits.search(new OrgUnitService.Search(null, null, null, null, asOf),
                    Page.of(first, ROWS_AT_A_TIME));
            units.addAll(page);
            if (page.size() < ROWS_AT_A_TIME) {
                break;
            }
        }
        SortedSet<String> names = new TreeSet<>();
        SortedSet<String> shortNames = new TreeSet<>();
        for (OrgUnitState unit : units) {
            names.addAll(unit.version().names().keySet());
            shortNames.addAll(unit.version().shortNames().keySet());
        }
        List<String> attributes = definitions.apply(AttributeDefinition.Target.ORG_UNIT).stream()
                .map(AttributeDefinition::name)
                .toList();

        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "code", "name")) {
            header.add(column(field, japanese));
        }
        names.forEach(language -> header.add(column("name", japanese) + ":" + language));
        header.add(column("shortName", japanese));
        shortNames.forEach(language -> header.add(column("shortName", japanese) + ":" + language));
        for (String field : List.of("phoneticName", "unitType", "parentId", "displayOrder", "validTo",
                "successorIds", "organizationAlias", "timeZone", "dayStart")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));

        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        for (OrgUnitState unit : units) {
            OrgUnitVersion version = unit.version();
            List<String> cells = new ArrayList<>(List.of(unit.unit().id(), text(unit.unit().externalId()),
                    text(unit.unit().source()), text(version.code()), version.name()));
            names.forEach(language -> cells.add(text(version.names().get(language))));
            cells.add(text(version.shortName()));
            shortNames.forEach(language -> cells.add(text(version.shortNames().get(language))));
            cells.add(text(version.phoneticName()));
            cells.add(text(unit.unitType()));
            cells.add(text(unit.parentId()));
            cells.add(Integer.toString(version.displayOrder()));
            // Only a unit whose details of the date end can end. The others need no more reads.
            Optional<LocalDate> end = version.period().to() == null ? Optional.empty()
                    : orgUnits.endOf(unit.unit().id());
            cells.add(text(end.orElse(null)));
            cells.add(end.isEmpty() ? "" : String.join("|", orgUnits.successions(unit.unit().id()).successors()
                    .stream().map(OrgUnitSuccession::successorId).toList()));
            cells.add(text(Optional.ofNullable(unit.unit().keycloakOrganizationId())
                    .flatMap(organizations::aliasOf).orElse(null)));
            cells.add(text(unit.unit().timeZone()));
            cells.add(text(unit.unit().dayStart()));
            attributes.forEach(attribute -> cells.add(text(Optional.ofNullable(version.attributes())
                    .map(values -> values.get(attribute)).orElse(null))));
            line(csv, cells);
        }
        return csv.toString();
    }

    private static String column(String field, boolean japanese) {
        return japanese ? CsvImportService.HEADERS.getOrDefault(field, field) : field;
    }

    // The database keeps two decimals, so 80.5 reads as 80.50.
    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    // A list is JSON, which the import reads for an attribute with several values.
    private static String text(Object value) {
        if (value instanceof List<?> list) {
            return Unchecked.io(() -> JSON.writeValueAsString(list));
        }
        return Objects.toString(value, "");
    }

    // A cell with a comma, a quote, or a line break is in quotes, with each quote doubled.
    private static void line(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            String cell = cells.get(i);
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
                csv.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                csv.append(cell);
            }
        }
        csv.append("\r\n");
    }
}
