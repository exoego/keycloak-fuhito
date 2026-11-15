package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;

/**
 * Writes a list of the realm on a date as a CSV that the CSV import loads again with no change.
 */
public class CsvExportService {

    // The mark that tells a spreadsheet that the file is UTF-8.
    private static final String BYTE_ORDER_MARK = "﻿";
    private static final int ROWS_AT_A_TIME = 1000;

    private final RefItemService refItems;
    private final Supplier<List<String>> kinds;
    private final OrgUnitService orgUnits;
    private final PersonService people;
    private final EmploymentService employments;
    private final Today today;
    private final Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions;

    /**
     * Creates the service.
     *
     * @param refItems the operations on reference items of the realm
     * @param kinds the codes of the kinds of reference items, the fixed ones and the ones of the realm
     * @param orgUnits the operations on org units of the realm
     * @param people the operations on people of the realm
     * @param employments the operations on employments of the realm
     * @param today what today is for the realm
     * @param definitions the definitions of free attributes of the realm, by the resource that has them
     */
    public CsvExportService(RefItemService refItems, Supplier<List<String>> kinds, OrgUnitService orgUnits,
            PersonService people, EmploymentService employments, Today today,
            Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions) {
        this.refItems = refItems;
        this.kinds = kinds;
        this.orgUnits = orgUnits;
        this.people = people;
        this.employments = employments;
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
     * @throws FuhitoException when the list is not one that can be written yet, or the date is bad
     */
    public String write(String list, String asOf, boolean japanese) {
        return switch (String.valueOf(list)) {
            case "ref-items" -> refItems(japanese);
            case "org-units" -> orgUnits(asOf, japanese);
            case "people" -> people(japanese);
            case "employments" -> employments(asOf, japanese);
            default -> throw FuhitoException.invalid("csv.export_list_unknown", Map.of("list", String.valueOf(list)));
        };
    }

    // A mark is left blank for a kind that cannot have it, as a blank cell means no mark for a new item.
    private String refItems(boolean japanese) {
        List<RefItem> items = new ArrayList<>();
        for (String kind : kinds.get()) {
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
        for (String field : List.of("displayOrder", "active", "legalEntity", "actingHead", "group")) {
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
            cells.add(unitType ? Boolean.toString(item.group()) : "");
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
                "employmentType", "grade", "status", "leaveReason", "workPercent", "validFrom", "validTo",
                "endReason")) {
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
                cells.add(employment.period().from().toString());
                cells.add(text(employment.period().to()));
                cells.add(text(view.endReason()));
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
        for (String field : List.of("phoneticName", "unitType", "parentId", "displayOrder")) {
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

    private static String text(Object value) {
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
