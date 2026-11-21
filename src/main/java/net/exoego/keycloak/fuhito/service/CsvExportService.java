package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;

/**
 * Writes a list of the realm on a date as a CSV that the CSV import loads again with no change.
 */
public class CsvExportService {

    // The mark that tells a spreadsheet that the file is UTF-8.
    private static final String BYTE_ORDER_MARK = "﻿";
    private static final int ROWS_AT_A_TIME = 1000;
    /** The lists, as the paths of the CSV export name them. */
    public static final List<String> LISTS = List.of("ref-kinds", "attribute-definitions", "ref-items",
            "hierarchies", "org-units", "org-unit-parents", "people", "employments", "assignments",
            "reporting-lines", "unit-roles", "qualifications", "external-ids", "change-orders");
    private static final Set<String> UNDATED = Set.of("ref-kinds", "attribute-definitions", "hierarchies", "people",
            "external-ids", "change-orders");

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
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today. For the qualifications, null is every date. A
     *        list with no date takes no date.
     * @param header {@code en} or null for the English names of the columns, or {@code ja} for the Japanese ones
     * @return the CSV
     * @throws FuhitoException when the list or the header is unknown, the date is bad, or the list has no date and
     *         the request gives one
     */
    public String write(String list, String asOf, String header) {
        boolean japanese = switch (Objects.requireNonNullElse(header, "en")) {
            case "en" -> false;
            case "ja" -> true;
            default -> throw FuhitoException.invalid("invalid.choice", Map.of("field", "header", "value", header));
        };
        String name = String.valueOf(list);
        if (Checks.optionalDate("asOf", asOf) != null && UNDATED.contains(name)) {
            throw FuhitoException.invalid("csv.export_no_date", Map.of("field", "asOf", "list", name));
        }
        return switch (name) {
            case "ref-kinds" -> refKinds(japanese);
            case "attribute-definitions" -> attributeDefinitions(japanese);
            case "hierarchies" -> hierarchies(japanese);
            case "org-unit-parents" -> orgUnitParents(asOf, japanese);
            case "ref-items" -> refItems(asOf, japanese);
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
        added.forEach(kind -> addOtherLanguages(kind.names(), languages));
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "code", "primaryLanguage", "name")) {
            header.add(column(field, japanese));
        }
        languages.forEach(language -> header.add(column("name", japanese) + ":" + language));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.REF_KIND,
                added.stream().map(RealmKindService.View::id).toList());
        for (RealmKindService.View kind : added) {
            List<String> cells = new ArrayList<>(withIds(ids, kind.id(), kind.code(), kind.names().primaryLanguage(),
                    kind.names().primary().name()));
            languages.forEach(language -> cells.add(nameCell(otherLanguage(kind.names(), language))));
            line(csv, cells);
        }
        return csv.toString();
    }

    private String hierarchies(boolean japanese) {
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, List.of(column("id", japanese), column("externalId", japanese), column("source", japanese),
                column("code", japanese), column("name", japanese)));
        for (int first = 0;; first += ROWS_AT_A_TIME) {
            List<Hierarchy> page = hierarchies.list(Page.of(first, ROWS_AT_A_TIME));
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.HIERARCHY,
                    page.stream().map(Hierarchy::id).toList());
            page.forEach(hierarchy -> line(csv, withIds(ids, hierarchy.id(), hierarchy.code(), hierarchy.name())));
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
        for (String field : List.of("id", "externalId", "source", "target", "name", "type", "refKind", "multiple",
                "required")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        for (AttributeDefinition.Target target : AttributeDefinition.Target.values()) {
            List<AttributeDefinition> defined = definitions.apply(target);
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.ATTRIBUTE_DEFINITION,
                    defined.stream().map(AttributeDefinition::id).toList());
            for (AttributeDefinition definition : defined) {
                line(csv, withIds(ids, definition.id(), target.name(), definition.name(), definition.type().name(),
                        text(definition.refKind()), Boolean.toString(definition.multiple()),
                        Boolean.toString(definition.required())));
            }
        }
        return csv.toString();
    }

    // A mark is left blank for a kind that cannot have it, as a blank cell means no mark for a new item.
    // The file has the names of the date and no validFrom, so that a changed name loads as names from the
    // effective date of the import.
    private String refItems(String asOf, boolean japanese) {
        List<RefItem> items = new ArrayList<>();
        for (String kind : kinds.get().stream().map(RealmKindService.View::code).toList()) {
            for (int first = 0;; first += ROWS_AT_A_TIME) {
                List<RefItem> page = refItems.list(kind, true, asOf, Page.of(first, ROWS_AT_A_TIME));
                items.addAll(page);
                if (page.size() < ROWS_AT_A_TIME) {
                    break;
                }
            }
        }
        SortedSet<String> languages = new TreeSet<>();
        items.forEach(item -> addOtherLanguages(item.names(), languages));
        List<String> attributes = definitions.apply(AttributeDefinition.Target.REF_ITEM).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "kind", "code", "primaryLanguage", "name")) {
            header.add(column(field, japanese));
        }
        languages.forEach(language -> header.add(column("name", japanese) + ":" + language));
        for (String field : List.of("displayOrder", "active", "legalEntity", "actingHead", "lateral", "rollup")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.REF_ITEM, items.stream().map(RefItem::id).toList());
        for (RefItem item : items) {
            List<String> cells = new ArrayList<>(withIds(ids, item.id(), item.kind(), item.code(),
                    item.names().primaryLanguage(), item.names().primary().name()));
            languages.forEach(language -> cells.add(nameCell(otherLanguage(item.names(), language))));
            cells.add(Integer.toString(item.displayOrder()));
            cells.add(Boolean.toString(item.active()));
            boolean unitType = item.is(RefKind.UNIT_TYPE);
            cells.add(unitType ? Boolean.toString(item.legalEntity()) : "");
            cells.add(item.is(RefKind.ASSIGNMENT_KIND) ? Boolean.toString(item.actingHead()) : "");
            cells.add(unitType ? Boolean.toString(item.lateral()) : "");
            cells.add(unitType ? Boolean.toString(item.rollup()) : "");
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
        eachPage(page -> page.stream().map(Person::names).filter(Objects::nonNull)
                .forEach(names -> addOtherLanguages(names, languages)));
        List<String> attributes = definitions.apply(AttributeDefinition.Target.PERSON).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> nameFields = List.of("name", "phoneticName");
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "primaryLanguage", "name", "phoneticName")) {
            header.add(column(field, japanese));
        }
        languages.forEach(language -> nameFields.forEach(field -> header.add(column(field, japanese) + ":"
                + language)));
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        eachPage(page -> {
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.PERSON, page.stream().map(Person::id).toList());
            for (Person person : page) {
                Localized<PersonName> names = person.names();
                List<String> cells = new ArrayList<>(List.of(person.id(),
                        text(ids.externalIdOf(person.id(), person.source())), text(person.source()),
                        names == null ? "" : names.primaryLanguage()));
                cells.addAll(nameCells(names == null ? null : names.primary()));
                languages.forEach(language -> cells.addAll(nameCells(names == null ? null
                        : otherLanguage(names, language))));
                attributes.forEach(attribute -> cells.add(text(Optional.ofNullable(person.attributes())
                        .map(values -> values.get(attribute)).orElse(null))));
                line(csv, cells);
            }
        });
        return csv.toString();
    }

    // The employments that hold on the date, with the terms of that date. The person and the employer are named by
    // ID, as many have no external ID of the source of the row. An external employer is named by its code.
    private String employments(String asOf, boolean japanese) {
        LocalDate date = asOf == null ? today.ofRealm() : Checks.date("asOf", asOf);
        List<String> attributes = definitions.apply(AttributeDefinition.Target.EMPLOYMENT_TERMS).stream()
                .map(AttributeDefinition::name)
                .toList();
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "personId", "staffNumber", "employerId",
                "externalEmployer", "employmentType", "grade", "status", "leaveReason", "workPercent",
                "contractEndDate", "validFrom", "validTo", "endReason", "continuesEmploymentId")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<EmploymentView> page = employments.list(new EmploymentService.Search(null, null, date.toString()),
                    Page.of(0, ROWS_AT_A_TIME), after);
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.EMPLOYMENT,
                    page.stream().map(view -> view.employment().id()).toList());
            for (EmploymentView view : page) {
                Employment employment = view.employment();
                Optional<EmploymentView.TermsView> terms = view.terms().stream()
                        .filter(t -> t.terms().period().contains(date))
                        .findFirst();
                List<String> cells = new ArrayList<>(List.of(employment.id(),
                        text(ids.externalIdOf(employment.id(), employment.source())), text(employment.source()), employment.personId(), text(employment.staffNumber()),
                        text(employment.employer().legalEntityId()), text(view.externalEmployer())));
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
                "occupation", "assignmentKind", "workLocation", "costCenter", "head", "primary", "allocationPercent",
                "displayOrder", "validFrom", "validTo", "continuesAssignmentId")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<AssignmentView> page = assignments.listOn(date, after, ROWS_AT_A_TIME);
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.ASSIGNMENT,
                    page.stream().map(AssignmentView::assignmentId).toList());
            for (AssignmentView view : page) {
                AssignmentView.Row current = view.rows().stream()
                        .filter(r -> r.row().period().contains(date))
                        .findFirst()
                        .orElseThrow();
                Assignment row = current.row();
                List<String> cells = new ArrayList<>(List.of(view.assignmentId(),
                        text(ids.externalIdOf(view.assignmentId(), row.source())), text(row.source()), row.employmentId(), row.orgUnitId(), text(current.jobTitle()),
                        text(current.occupation()), text(current.kind()), text(current.places().workLocation()),
                        text(current.places().costCenter()), Boolean.toString(row.head()),
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
        for (String field : List.of("id", "externalId", "source", "assignmentId", "managerPersonId", "kind",
                "validFrom", "validTo")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<ReportingLine> page = reportingLines.listOn(date, after, ROWS_AT_A_TIME);
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.REPORTING_LINE,
                    page.stream().map(ReportingLine::id).toList());
            page.forEach(l -> line(csv, withIds(ids, l.id(), l.assignmentId(), l.managerPersonId(),
                    l.kind().name(), l.period().from().toString(), text(l.period().to()))));
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = page.getLast().id();
        }
    }

    // Every order, newest effective date first. The announcement is a fact of its own, so it is not in the file.
    private String changeOrders(boolean japanese) {
        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "number", "kind", "title", "note", "orderDate",
                "effectiveDate", "approverUserId", "requesterUserId", "externalReference")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        for (int first = 0;; first += ROWS_AT_A_TIME) {
            List<ChangeOrder> page = changeOrders.list(null, null, first, ROWS_AT_A_TIME);
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.CHANGE_ORDER,
                    page.stream().map(ChangeOrder::id).toList());
            page.forEach(order -> line(csv, withIds(ids, order.id(), text(order.number()), text(order.kind()),
                    order.title(), text(order.note()), text(order.orderDate()), order.effectiveDate().toString(),
                    text(order.approverUserId()), text(order.requesterUserId()), text(order.externalReference()))));
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
        }
    }

    // Every ID of every system. The list of a resource has the ID of the system that made it again.
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
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.QUALIFICATION,
                    page.stream().map(view -> view.qualification().id()).toList());
            for (QualificationView view : page) {
                Qualification q = view.qualification();
                List<String> cells = new ArrayList<>(List.of(q.id(), text(ids.externalIdOf(q.id(), q.source())),
                        text(q.source()),
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
        for (String field : List.of("id", "externalId", "source", "employmentId", "orgUnitId", "role",
                "includesDescendants", "validFrom", "validTo")) {
            header.add(column(field, japanese));
        }
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        String after = null;
        while (true) {
            List<UnitRoleService.View> page = unitRoles.listOn(date, after, ROWS_AT_A_TIME);
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.UNIT_ROLE,
                    page.stream().map(view -> view.role().id()).toList());
            for (UnitRoleService.View view : page) {
                UnitRole role = view.role();
                line(csv, withIds(ids, role.id(), role.employmentId(), role.orgUnitId(), text(view.code()),
                        Boolean.toString(role.includesDescendants()), role.period().from().toString(),
                        text(role.period().to())));
            }
            if (page.size() < ROWS_AT_A_TIME) {
                return csv.toString();
            }
            after = page.getLast().role().id();
        }
    }

    private void eachPage(Consumer<List<Person>> each) {
        String after = null;
        while (true) {
            List<PersonView> page = people.list(null, null, ROWS_AT_A_TIME, after);
            each.accept(page.stream().map(PersonView::person).toList());
            if (page.size() < ROWS_AT_A_TIME) {
                return;
            }
            after = Cursor.encode(Cursor.PEOPLE, page.getLast().person().id());
        }
    }

    private String orgUnits(String asOf, boolean japanese) {
        List<OrgUnitState> units = new ArrayList<>();
        for (int first = 0;; first += ROWS_AT_A_TIME) {
            List<OrgUnitState> page = orgUnits.list(OrgUnitService.Search.all(asOf), Page.of(first, ROWS_AT_A_TIME),
                    null);
            units.addAll(page);
            if (page.size() < ROWS_AT_A_TIME) {
                break;
            }
        }
        SortedSet<String> languages = new TreeSet<>();
        units.forEach(unit -> addOtherLanguages(unit.version().names(), languages));
        List<String> nameFields = List.of("name", "shortName", "phoneticName");
        List<String> attributes = definitions.apply(AttributeDefinition.Target.ORG_UNIT).stream()
                .map(AttributeDefinition::name)
                .toList();

        List<String> header = new ArrayList<>();
        for (String field : List.of("id", "externalId", "source", "code", "primaryLanguage", "name", "shortName",
                "phoneticName")) {
            header.add(column(field, japanese));
        }
        languages.forEach(language -> nameFields.forEach(field -> header.add(column(field, japanese) + ":"
                + language)));
        for (String field : List.of("unitType", "workLocation", "costCenter", "parentId",
                "displayOrder", "validTo", "successorIds", "organizationAlias", "timeZone", "dayStart")) {
            header.add(column(field, japanese));
        }
        attributes.forEach(attribute -> header.add(CsvImportService.ATTRIBUTE_COLUMN + attribute));

        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, header);
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.ORG_UNIT,
                units.stream().map(unit -> unit.unit().id()).toList());
        for (OrgUnitState unit : units) {
            OrgUnitVersion version = unit.version();
            List<String> cells = new ArrayList<>(List.of(unit.unit().id(),
                    text(ids.externalIdOf(unit.unit().id(), unit.unit().source())), text(unit.unit().source()),
                    text(version.code()), version.names().primaryLanguage()));
            cells.addAll(nameCells(version.names().primary()));
            languages.forEach(language -> cells.addAll(nameCells(otherLanguage(version.names(), language))));
            cells.add(text(unit.unitType()));
            cells.add(text(unit.places().workLocation()));
            cells.add(text(unit.places().costCenter()));
            cells.add(text(unit.parentId()));
            cells.add(Integer.toString(version.displayOrder()));
            // Only a unit whose details of the date end can end. The others need no more reads.
            Optional<LocalDate> end = version.period().to() == null ? Optional.empty()
                    : orgUnits.endOf(unit.unit().id());
            cells.add(text(end.orElse(null)));
            cells.add(end.isEmpty() ? "" : ListCell.write(orgUnits.successions(unit.unit().id()).successors()
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

    // These kinds keep no system that made them, so a row has the first of their IDs by system. Any ID of the
    // thing loads again, as the file has its own ID too.
    private static List<String> withIds(ExternalIdLists ids, String id, String... cells) {
        List<String> row = new ArrayList<>(List.of(id));
        row.addAll(firstExternalId(ids, id));
        row.addAll(List.of(cells));
        return row;
    }

    private static List<String> firstExternalId(ExternalIdLists ids, String id) {
        return ids.of(id, null).stream()
                .findFirst()
                .map(row -> List.of(row.externalId(), row.source()))
                .orElse(List.of("", ""));
    }

    // The columns with no language have the primary names. The other languages have columns of their own.
    private static void addOtherLanguages(Localized<?> names, Set<String> languages) {
        names.byLanguage().keySet().stream()
                .filter(language -> !language.equals(names.primaryLanguage()))
                .forEach(languages::add);
    }

    // A file leaves the columns of a language empty for a resource whose primary language it is.
    private static <T> T otherLanguage(Localized<T> names, String language) {
        return language.equals(names.primaryLanguage()) ? null : names.byLanguage().get(language);
    }

    private static List<String> nameCells(OrgUnitName names) {
        return names == null ? List.of("", "", "")
                : List.of(names.name(), text(names.shortName()), text(names.phoneticName()));
    }

    private static List<String> nameCells(PersonName names) {
        return names == null ? List.of("", "") : List.of(names.name(), text(names.phoneticName()));
    }

    private static String nameCell(RefItemName name) {
        return name == null ? "" : name.name();
    }

    private static String nameCell(RefKindName name) {
        return name == null ? "" : name.name();
    }

    private static String column(String field, boolean japanese) {
        return japanese ? CsvImportService.HEADERS.getOrDefault(field, field) : field;
    }

    // The database keeps two decimals, so 80.5 reads as 80.50.
    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String text(Object value) {
        if (value instanceof List<?> list) {
            return ListCell.write(list);
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
