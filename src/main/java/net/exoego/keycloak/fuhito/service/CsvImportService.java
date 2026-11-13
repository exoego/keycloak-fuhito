package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Csv;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefItemRow;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Turns one CSV file into an import document and applies it.
 * A blank cell keeps the stored value. The cell {@code #CLEAR} removes it.
 */
public class CsvImportService {

    /**
     * The cell that removes a stored value.
     */
    public static final String CLEAR = "#CLEAR";

    /**
     * What a CSV import did, with the line of each bad row.
     *
     * @param report the counts and the writes
     * @param errors the rows that could not be applied
     */
    public record Result(ImportReport report, List<LineError> errors) {
    }

    /**
     * A row that could not be applied.
     *
     * @param list the name of the list
     * @param row the position of the row among the data rows, counted from 0
     * @param line the line number in the file, counted from 1
     * @param error the cause
     */
    public record LineError(String list, int row, int line, FuhitoException error) {
    }

    // Header names in English and Japanese, by the field they fill.
    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("kind", "種類"), Map.entry("code", "コード"), Map.entry("name", "名称"),
            Map.entry("displayOrder", "表示順"), Map.entry("active", "有効"), Map.entry("externalId", "外部ID"),
            Map.entry("source", "ソース"), Map.entry("shortName", "略称"), Map.entry("phoneticName", "よみがな"),
            Map.entry("unitType", "組織種別"), Map.entry("parentExternalId", "親の外部ID"),
            Map.entry("validFrom", "開始日"), Map.entry("validTo", "終了日"), Map.entry("username", "ユーザー名"),
            Map.entry("userId", "ユーザーID"), Map.entry("staffNumber", "職員番号"),
            Map.entry("employmentType", "雇用形態"), Map.entry("grade", "等級"), Map.entry("status", "状態"),
            Map.entry("workPercent", "勤務割合"), Map.entry("endReason", "終了理由"),
            Map.entry("employmentExternalId", "雇用の外部ID"), Map.entry("orgUnitExternalId", "組織の外部ID"),
            Map.entry("jobTitle", "役職"), Map.entry("occupation", "職種"), Map.entry("assignmentKind", "所属区分"),
            Map.entry("head", "長"), Map.entry("primary", "主務"), Map.entry("allocationPercent", "配分割合"));

    private static final Map<String, Set<String>> FIELDS = Map.of(
            "refItems", Set.of("kind", "code", "name", "displayOrder", "active"),
            "orgUnits", Set.of("externalId", "source", "code", "name", "shortName", "phoneticName", "unitType",
                    "parentExternalId", "displayOrder", "validFrom", "validTo"),
            "employments", Set.of("externalId", "source", "username", "userId", "staffNumber", "employmentType",
                    "grade", "status", "workPercent", "validFrom", "validTo", "endReason"),
            "assignments", Set.of("externalId", "source", "employmentExternalId", "orgUnitExternalId", "jobTitle",
                    "occupation", "assignmentKind", "head", "primary", "allocationPercent", "displayOrder",
                    "validFrom", "validTo"));

    private static final Set<String> LOCALIZED = Set.of("name", "shortName");
    private static final Set<String> TRUE = Set.of("true", "1", "yes", "y", "はい", "○", "〇");
    private static final Set<String> FALSE = Set.of("false", "0", "no", "n", "いいえ", "×");

    private final ImportService importService;
    private final RefItemStore refItems;
    private final OrgUnitStore orgUnits;
    private final OrgUnitService orgUnitService;
    private final ImportService.People people;

    /**
     * Creates the service.
     *
     * @param importService the import that applies the rows
     * @param refItems the storage of reference items of the realm
     * @param orgUnits the storage of org units of the realm
     * @param orgUnitService the operations on org units of the realm
     * @param people what an import of employments and assignments needs
     */
    public CsvImportService(ImportService importService, RefItemStore refItems, OrgUnitStore orgUnits,
            OrgUnitService orgUnitService, ImportService.People people) {
        this.importService = importService;
        this.refItems = refItems;
        this.orgUnits = orgUnits;
        this.orgUnitService = orgUnitService;
        this.people = people;
    }

    /**
     * Applies one CSV file.
     *
     * @param list the list that the file holds. It is {@code refItems}, {@code orgUnits}, {@code employments},
     *        or {@code assignments}.
     * @param csv the text of the file
     * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
     * @param dryRun true to do all checks and write nothing
     * @param source the name of the system that the rows come from, or null for "import"
     * @return the counts, the writes, and the bad rows with their lines
     * @throws FuhitoException when the file as a whole is bad, for example with an unknown column
     */
    public Result apply(String list, String csv, String effectiveDate, boolean dryRun, String source) {
        Set<String> fields = FIELDS.get(Checks.required("list", list));
        if (fields == null) {
            throw FuhitoException.invalid("invalid.choice", Map.of("field", "list", "value", list));
        }
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        String defaultSource = Checks.optionalCode("source", source) == null ? ImportService.DEFAULT_SOURCE : source;
        List<Csv.Row> rows = Csv.parse(Checks.required("body", csv));
        if (rows.isEmpty()) {
            throw FuhitoException.invalid("csv.no_header", Map.of());
        }
        List<String> columns = columns(rows.get(0).cells(), fields);
        List<Csv.Row> data = rows.subList(1, rows.size());

        Map<Integer, FuhitoException> conversionErrors = new HashMap<>();
        List<Object> converted = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            try {
                converted.add(convert(list, new Cells(columns, data.get(i).cells()), date, defaultSource));
            } catch (FuhitoException e) {
                conversionErrors.put(i, e);
                converted.add(null);
            }
        }
        ImportReport report = importService.apply(document(list, converted, effectiveDate, dryRun, defaultSource));
        List<LineError> errors = report.errors().stream()
                .map(e -> new LineError(e.list(), e.row(), data.get(e.row()).line(),
                        conversionErrors.getOrDefault(e.row(), e.error())))
                .toList();
        return new Result(report, errors);
    }

    private static List<String> columns(List<String> header, Set<String> fields) {
        Map<String, String> byName = new HashMap<>();
        HEADERS.forEach((field, japanese) -> {
            byName.put(field.toLowerCase(Locale.ROOT), field);
            byName.put(japanese, field);
        });
        List<String> columns = new ArrayList<>();
        for (String cell : header) {
            String name = cell.strip();
            int colon = name.indexOf(':');
            String base = colon < 0 ? name : name.substring(0, colon);
            String field = byName.get(base);
            if (field == null) {
                field = byName.get(base.toLowerCase(Locale.ROOT));
            }
            boolean localized = colon >= 0;
            if (field == null || !fields.contains(field) || (localized && !LOCALIZED.contains(field))
                    || columns.contains(localized ? field + name.substring(colon) : field)) {
                throw FuhitoException.invalid("csv.bad_column", Map.of("column", name));
            }
            columns.add(localized ? field + name.substring(colon) : field);
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private static ImportDocument document(String list, List<Object> rows, String effectiveDate, boolean dryRun,
            String source) {
        return new ImportDocument(effectiveDate, dryRun, source,
                list.equals("refItems") ? (List<RefItemRow>) (List<?>) rows : null,
                list.equals("orgUnits") ? (List<OrgUnitRow>) (List<?>) rows : null,
                list.equals("employments") ? (List<EmploymentRow>) (List<?>) rows : null,
                list.equals("assignments") ? (List<AssignmentRow>) (List<?>) rows : null);
    }

    private Object convert(String list, Cells cells, LocalDate date, String defaultSource) {
        return switch (list) {
            case "refItems" -> refItem(cells);
            case "orgUnits" -> orgUnit(cells, date, defaultSource);
            case "employments" -> employment(cells, date, defaultSource);
            default -> assignment(cells, date, defaultSource);
        };
    }

    private RefItemRow refItem(Cells cells) {
        String kind = cells.text("kind", null);
        String code = cells.text("code", null);
        Optional<RefItem> stored = Optional.empty();
        if (kind != null && code != null) {
            try {
                stored = refItems.findByCode(RefKind.valueOf(kind), code);
            } catch (IllegalArgumentException e) {
                // The import reports the unknown kind.
            }
        }
        return new RefItemRow(kind, code, cells.text("name", stored.map(RefItem::name).orElse(null)),
                cells.localized("name", stored.map(RefItem::names).orElse(Map.of())),
                cells.integer("displayOrder", stored.map(RefItem::displayOrder).orElse(null)),
                stored.map(RefItem::attributes).orElse(null),
                cells.bool("active", stored.map(RefItem::active).orElse(null)));
    }

    private OrgUnitRow orgUnit(Cells cells, LocalDate date, String defaultSource) {
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        Optional<OrgUnitState> stored = Optional.empty();
        if (externalId != null) {
            stored = orgUnits.findUnitByExternalId(source == null ? defaultSource : source, externalId)
                    .flatMap(unit -> quietly(() -> orgUnitService.get(unit.id(), date.toString())));
        }
        var version = stored.map(OrgUnitState::version);
        String storedParent = stored.map(OrgUnitState::parentId)
                .flatMap(orgUnits::findUnit)
                .map(OrgUnit::externalId)
                .orElse(null);
        return new OrgUnitRow(source, externalId, cells.text("code", null),
                cells.text("name", version.map(v -> v.name()).orElse(null)),
                cells.localized("name", version.map(v -> v.names()).orElse(Map.of())),
                cells.text("shortName", version.map(v -> v.shortName()).orElse(null)),
                cells.localized("shortName", version.map(v -> v.shortNames()).orElse(Map.of())),
                cells.text("phoneticName", version.map(v -> v.phoneticName()).orElse(null)),
                cells.text("unitType", stored.map(OrgUnitState::unitType).orElse(null)),
                cells.text("parentExternalId", storedParent),
                cells.integer("displayOrder", version.map(v -> v.displayOrder()).orElse(null)),
                version.map(v -> v.attributes()).orElse(null),
                cells.text("validFrom", null), cells.text("validTo", null));
    }

    private EmploymentRow employment(Cells cells, LocalDate date, String defaultSource) {
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        Optional<Employment> employment = externalId == null ? Optional.empty()
                : people.employments().findByExternalId(source == null ? defaultSource : source, externalId);
        Optional<EmploymentView.TermsView> terms = employment
                .map(e -> people.employmentService().get(e.id()))
                .flatMap(view -> view.terms().stream().filter(t -> t.terms().period().contains(date)).findFirst());
        // A row for a stored employment may leave out the user.
        String userId = cells.text("userId", null);
        String username = cells.text("username", null);
        if (userId == null && username == null) {
            userId = employment.map(Employment::userId).orElse(null);
        }
        return new EmploymentRow(source, externalId, username, userId, cells.text("staffNumber", null),
                cells.text("employmentType", terms.map(EmploymentView.TermsView::employmentType).orElse(null)),
                cells.text("grade", terms.map(EmploymentView.TermsView::grade).orElse(null)),
                cells.text("status", terms.map(t -> t.terms().status().name()).orElse(null)),
                cells.decimal("workPercent", terms.map(t -> t.terms().workPercent()).orElse(null)),
                terms.map(t -> t.terms().attributes()).orElse(null),
                cells.text("validFrom", null), cells.text("validTo", null), cells.text("endReason", null));
    }

    private AssignmentRow assignment(Cells cells, LocalDate date, String defaultSource) {
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        Optional<AssignmentView> view = externalId == null ? Optional.empty()
                : people.assignments().findAssignmentIdByExternalId(source == null ? defaultSource : source,
                        externalId).map(people.assignmentService()::get);
        Optional<AssignmentView.Row> current = view
                .flatMap(v -> v.rows().stream().filter(r -> r.row().period().contains(date)).findFirst());
        Optional<Assignment> row = current.map(AssignmentView.Row::row);
        // A row for a stored assignment may leave out the employment and the org unit.
        String employmentExternalId = cells.text("employmentExternalId", row.map(Assignment::employmentId)
                .flatMap(people.employments()::find).map(Employment::externalId).orElse(null));
        String orgUnitExternalId = cells.text("orgUnitExternalId", row.map(Assignment::orgUnitId)
                .flatMap(orgUnits::findUnit).map(OrgUnit::externalId).orElse(null));
        return new AssignmentRow(source, externalId, employmentExternalId, orgUnitExternalId,
                cells.text("jobTitle", current.map(AssignmentView.Row::jobTitle).orElse(null)),
                cells.text("occupation", current.map(AssignmentView.Row::occupation).orElse(null)),
                cells.text("assignmentKind", current.map(AssignmentView.Row::kind).orElse(null)),
                cells.bool("head", row.map(Assignment::head).orElse(null)),
                cells.bool("primary", row.map(Assignment::primary).orElse(null)),
                cells.decimal("allocationPercent", row.map(Assignment::allocationPercent).orElse(null)),
                cells.integer("displayOrder", row.map(Assignment::displayOrder).orElse(null)),
                row.map(Assignment::attributes).orElse(null),
                cells.text("validFrom", null), cells.text("validTo", null));
    }

    private static <T> Optional<T> quietly(Supplier<T> read) {
        try {
            return Optional.of(read.get());
        } catch (FuhitoException e) {
            // The import reports why the stored row cannot be read on the date.
            return Optional.empty();
        }
    }

    /**
     * The cells of one row by column.
     */
    private static final class Cells {

        private final Map<String, String> values = new LinkedHashMap<>();

        Cells(List<String> columns, List<String> cells) {
            for (int i = 0; i < columns.size(); i++) {
                values.put(columns.get(i), i < cells.size() ? cells.get(i).strip() : "");
            }
        }

        String text(String field, String stored) {
            String value = values.getOrDefault(field, "");
            if (value.isEmpty()) {
                return stored;
            }
            return value.equals(CLEAR) ? null : value;
        }

        Integer integer(String field, Integer stored) {
            String value = text(field, null);
            if (value == null) {
                return values.getOrDefault(field, "").equals(CLEAR) ? null : stored;
            }
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException e) {
                throw FuhitoException.invalid("csv.bad_number", Map.of("field", field, "value", value));
            }
        }

        BigDecimal decimal(String field, BigDecimal stored) {
            String value = text(field, null);
            if (value == null) {
                return values.getOrDefault(field, "").equals(CLEAR) ? null : stored;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw FuhitoException.invalid("csv.bad_number", Map.of("field", field, "value", value));
            }
        }

        Boolean bool(String field, Boolean stored) {
            String value = text(field, null);
            if (value == null) {
                return values.getOrDefault(field, "").equals(CLEAR) ? null : stored;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (TRUE.contains(lower)) {
                return true;
            }
            if (FALSE.contains(lower)) {
                return false;
            }
            throw FuhitoException.invalid("csv.bad_boolean", Map.of("field", field, "value", value));
        }

        // A column like "name:en" sets one language. The other languages keep what is stored.
        Map<String, String> localized(String field, Map<String, String> stored) {
            Map<String, String> result = new LinkedHashMap<>(stored);
            values.forEach((column, value) -> {
                if (column.startsWith(field + ":") && !value.isEmpty()) {
                    String language = column.substring(field.length() + 1);
                    if (value.equals(CLEAR)) {
                        result.remove(language);
                    } else {
                        result.put(language, value);
                    }
                }
            });
            return result;
        }
    }
}
