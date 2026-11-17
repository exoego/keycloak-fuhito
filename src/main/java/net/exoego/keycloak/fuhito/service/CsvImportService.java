package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Csv;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.AttributeDefinitionRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.ChangeOrderRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.ExternalIdRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.HierarchyRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitParentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.PersonRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.QualificationRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefItemRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.ReportingLineRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.UnitRoleRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefKindRow;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
    static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("kind", "種類"), Map.entry("code", "コード"), Map.entry("name", "名称"),
            Map.entry("displayOrder", "表示順"), Map.entry("active", "有効"), Map.entry("externalId", "外部ID"),
            Map.entry("source", "ソース"), Map.entry("shortName", "略称"), Map.entry("phoneticName", "よみがな"),
            Map.entry("unitType", "組織種別"), Map.entry("parentExternalId", "親の外部ID"),
            Map.entry("validFrom", "開始日"), Map.entry("validTo", "終了日"), Map.entry("username", "ユーザー名"),
            Map.entry("userId", "ユーザーID"), Map.entry("staffNumber", "職員番号"),
            Map.entry("employmentType", "雇用形態"), Map.entry("grade", "等級"), Map.entry("status", "状態"),
            Map.entry("leaveReason", "休職理由"), Map.entry("workPercent", "勤務割合"),
            Map.entry("contractEndDate", "契約期間満了日"), Map.entry("endReason", "終了理由"),
            Map.entry("employmentExternalId", "雇用の外部ID"), Map.entry("orgUnitExternalId", "組織の外部ID"),
            Map.entry("jobTitle", "役職"), Map.entry("occupation", "職種"), Map.entry("assignmentKind", "所属区分"),
            Map.entry("employerExternalId", "雇用主の外部ID"), Map.entry("legalEntity", "法人"), Map.entry("actingHead", "代行"),
            Map.entry("lateral", "横断"), Map.entry("personExternalId", "人の外部ID"),
            Map.entry("head", "長"), Map.entry("primary", "主務"), Map.entry("allocationPercent", "配分割合"),
            Map.entry("id", "ID"), Map.entry("parentId", "親のID"), Map.entry("personId", "人のID"),
            Map.entry("employerId", "雇用主のID"), Map.entry("employmentId", "雇用のID"),
            Map.entry("orgUnitId", "組織のID"), Map.entry("target", "対象"), Map.entry("type", "型"),
            Map.entry("refKind", "参照の種類"), Map.entry("multiple", "複数"), Map.entry("required", "必須"), Map.entry("hierarchy", "階層"),
            Map.entry("successorExternalIds", "後継の外部ID"), Map.entry("successorIds", "後継のID"),
            Map.entry("organizationAlias", "Organizationの別名"), Map.entry("assignmentExternalId", "所属の外部ID"),
            Map.entry("assignmentId", "所属のID"), Map.entry("managerPersonExternalId", "上司の人の外部ID"),
            Map.entry("managerPersonId", "上司の人のID"), Map.entry("managerUsername", "上司のユーザー名"),
            Map.entry("role", "ロール"), Map.entry("includesDescendants", "下位の組織も含む"),
            Map.entry("number", "番号"), Map.entry("resourceType", "リソースの種類"),
            Map.entry("resourceId", "リソースのID"), Map.entry("title", "件名"), Map.entry("note", "備考"),
            Map.entry("orderDate", "発令日"), Map.entry("effectiveDate", "発効日"),
            Map.entry("approverUserId", "承認者のユーザーID"), Map.entry("approverUsername", "承認者のユーザー名"),
            Map.entry("requesterUserId", "申請者のユーザーID"), Map.entry("requesterUsername", "申請者のユーザー名"),
            Map.entry("externalReference", "外部の参照"), Map.entry("timeZone", "タイムゾーン"),
            Map.entry("dayStart", "日の始まり"),
            Map.entry("continuesEmploymentExternalId", "引き継ぎ元の雇用の外部ID"),
            Map.entry("continuesEmploymentId", "引き継ぎ元の雇用のID"),
            Map.entry("continuesAssignmentExternalId", "引き継ぎ元の所属の外部ID"),
            Map.entry("continuesAssignmentId", "引き継ぎ元の所属のID"));

    // The lists that a file can hold, each with the resource whose attributes it sets.
    private enum Kind {
        REF_KINDS("refKinds", null),
        ATTRIBUTE_DEFINITIONS("attributeDefinitions", null),
        HIERARCHIES("hierarchies", null),
        ORG_UNIT_PARENTS("orgUnitParents", null),
        REPORTING_LINES("reportingLines", null),
        UNIT_ROLES("unitRoles", null),
        QUALIFICATIONS("qualifications", AttributeDefinition.Target.QUALIFICATION),
        EXTERNAL_IDS("externalIds", null),
        CHANGE_ORDERS("changeOrders", null),
        REF_ITEMS("refItems", AttributeDefinition.Target.REF_ITEM),
        ORG_UNITS("orgUnits", AttributeDefinition.Target.ORG_UNIT),
        EMPLOYMENTS("employments", AttributeDefinition.Target.EMPLOYMENT_TERMS),
        ASSIGNMENTS("assignments", AttributeDefinition.Target.ASSIGNMENT),
        PEOPLE("people", AttributeDefinition.Target.PERSON);

        private final String list;
        private final AttributeDefinition.Target target;

        Kind(String list, AttributeDefinition.Target target) {
            this.list = list;
            this.target = target;
        }

        static Kind of(String list) {
            return Arrays.stream(values())
                    .filter(kind -> kind.list.equals(list))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.invalid("invalid.choice",
                            Map.of("field", "list", "value", list)));
        }
    }

    private static final Map<Kind, Set<String>> FIELDS = Map.ofEntries(
            Map.entry(Kind.REF_KINDS, Set.of("code", "name")),
            Map.entry(Kind.ATTRIBUTE_DEFINITIONS,
                    Set.of("target", "name", "type", "refKind", "multiple", "required")),
            Map.entry(Kind.HIERARCHIES, Set.of("code", "name")),
            Map.entry(Kind.ORG_UNIT_PARENTS, Set.of("hierarchy", "source", "orgUnitExternalId", "orgUnitId",
                    "parentExternalId", "parentId")),
            Map.entry(Kind.EXTERNAL_IDS, Set.of("resourceType", "resourceId", "source", "externalId")),
            Map.entry(Kind.CHANGE_ORDERS, Set.of("id", "number", "kind", "title", "note", "orderDate",
                    "effectiveDate", "approverUserId", "approverUsername", "requesterUserId", "requesterUsername",
                    "externalReference")),
            Map.entry(Kind.QUALIFICATIONS, Set.of("id", "source", "externalId", "personExternalId", "personId",
                    "username", "kind", "number", "validFrom", "validTo")),
            Map.entry(Kind.UNIT_ROLES, Set.of("id", "source", "employmentExternalId", "employmentId",
                    "orgUnitExternalId", "orgUnitId", "role", "includesDescendants", "validFrom", "validTo")),
            Map.entry(Kind.REPORTING_LINES, Set.of("id", "source", "assignmentExternalId", "assignmentId",
                    "managerPersonExternalId", "managerPersonId", "managerUsername", "kind", "validFrom", "validTo")),
            Map.entry(Kind.REF_ITEMS, Set.of("kind", "code", "name", "displayOrder", "active", "legalEntity",
                    "actingHead", "lateral")),
            Map.entry(Kind.ORG_UNITS, Set.of("id", "externalId", "source", "code", "name", "shortName",
                    "phoneticName", "unitType", "parentExternalId", "parentId", "displayOrder", "validFrom",
                    "validTo", "successorExternalIds", "successorIds", "organizationAlias", "timeZone", "dayStart")),
            Map.entry(Kind.EMPLOYMENTS, Set.of("id", "externalId", "source", "username", "userId", "staffNumber",
                    "employmentType", "grade", "status", "leaveReason", "workPercent", "contractEndDate", "validFrom",
                    "validTo", "endReason", "employerExternalId", "employerId", "personExternalId", "personId",
                    "continuesEmploymentExternalId", "continuesEmploymentId")),
            Map.entry(Kind.PEOPLE, Set.of("id", "externalId", "source", "username", "userId", "name",
                    "phoneticName")),
            Map.entry(Kind.ASSIGNMENTS, Set.of("id", "externalId", "source", "employmentExternalId", "employmentId",
                    "orgUnitExternalId", "orgUnitId", "jobTitle", "occupation", "assignmentKind", "head", "primary",
                    "allocationPercent", "displayOrder", "validFrom", "validTo", "continuesAssignmentExternalId",
                    "continuesAssignmentId")));

    private static final Set<String> LOCALIZED = Set.of("name", "shortName");
    static final String ATTRIBUTE_COLUMN = "attributes.";
    private static final Set<String> TRUE = Set.of("true", "1", "yes", "y", "はい", "○", "〇");
    private static final Set<String> FALSE = Set.of("false", "0", "no", "n", "いいえ", "×");

    private final Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions;
    private final ImportService importService;
    private final RefItemStore refItems;
    private final OrgUnitStore orgUnits;
    private final OrgUnitService orgUnitService;
    private final ImportService.People people;

    /**
     * Creates the service. A file can set the defined attributes of the realm in columns of their own.
     *
     * @param importService the import that writes the rows
     * @param refItems the reference items of the realm
     * @param orgUnits the storage of org units of the realm
     * @param orgUnitService the operations on org units of the realm
     * @param people the stores and services of people of the realm
     * @param definitions the definitions of attributes, by kind of resource
     */
    public CsvImportService(ImportService importService, RefItemStore refItems, OrgUnitStore orgUnits,
            OrgUnitService orgUnitService, ImportService.People people,
            Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions) {
        this.definitions = definitions;
        this.importService = importService;
        this.refItems = refItems;
        this.orgUnits = orgUnits;
        this.orgUnitService = orgUnitService;
        this.people = people;
    }

    /**
     * Applies one CSV file.
     *
     * @param list the list that the file holds, such as {@code refKinds}, {@code refItems}, or {@code orgUnits}
     * @param csv the text of the file
     * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
     * @param dryRun true to do all checks and write nothing
     * @param source the name of the system that the rows come from, or null for "import"
     * @return the counts, the writes, and the bad rows with their lines
     * @throws FuhitoException when the file as a whole is bad, for example with an unknown column
     */
    public Result apply(String list, String csv, String effectiveDate, boolean dryRun, String source) {
        Kind kind = Kind.of(Checks.required("list", list));
        LocalDate date = Checks.date("effectiveDate", effectiveDate);
        String defaultSource = Checks.optionalCode("source", source) == null ? ImportService.DEFAULT_SOURCE : source;
        List<Csv.Row> rows = Csv.parse(Checks.required("body", csv));
        if (rows.isEmpty()) {
            throw FuhitoException.invalid("csv.no_header", Map.of());
        }
        List<AttributeDefinition> defined = kind.target == null ? List.of() : definitions.apply(kind.target);
        List<String> columns = columns(rows.getFirst().cells(), FIELDS.get(kind), defined);
        List<Csv.Row> data = rows.subList(1, rows.size());

        // Each list has its own type of row, so the rows keep that type from the cells to the import.
        Function<List<?>, ImportDocument> document = typed -> ImportDocument.of(effectiveDate, dryRun,
                defaultSource, kind.list, typed);
        return switch (kind) {
            case REF_KINDS -> run(data, columns, defined, this::refKind, document);
            case ATTRIBUTE_DEFINITIONS -> run(data, columns, defined, this::attributeDefinition, document);
            case HIERARCHIES -> run(data, columns, defined, this::hierarchy, document);
            case ORG_UNIT_PARENTS -> run(data, columns, defined, cells -> orgUnitParent(cells, date, defaultSource),
                    document);
            case REPORTING_LINES -> run(data, columns, defined, this::reportingLine, document);
            case UNIT_ROLES -> run(data, columns, defined, this::unitRole, document);
            case QUALIFICATIONS -> run(data, columns, defined, cells -> qualification(cells, defaultSource),
                    document);
            case EXTERNAL_IDS -> run(data, columns, defined, cells -> externalId(cells, defaultSource), document);
            case CHANGE_ORDERS -> run(data, columns, defined, this::changeOrder, document);
            case REF_ITEMS -> run(data, columns, defined, this::refItem, document);
            case ORG_UNITS -> run(data, columns, defined, cells -> orgUnit(cells, date, defaultSource), document);
            case EMPLOYMENTS -> run(data, columns, defined, cells -> employment(cells, date, defaultSource),
                    document);
            case ASSIGNMENTS -> run(data, columns, defined, cells -> assignment(cells, date, defaultSource),
                    document);
            case PEOPLE -> run(data, columns, defined, cells -> person(cells, defaultSource), document);
        };
    }

    private <R> Result run(List<Csv.Row> data, List<String> columns, List<AttributeDefinition> defined,
            Function<Cells, R> parse, Function<List<?>, ImportDocument> document) {
        Map<Integer, FuhitoException> conversionErrors = new HashMap<>();
        List<R> converted = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            try {
                converted.add(parse.apply(Cells.of(columns, data.get(i).cells(), defined)));
            } catch (FuhitoException e) {
                // The row keeps its place as null. The import reports a missing row there, and the cause
                // from here replaces that error below.
                conversionErrors.put(i, e);
                converted.add(null);
            }
        }
        ImportReport report = importService.apply(document.apply(converted));
        List<LineError> errors = report.errors().stream()
                .map(e -> new LineError(e.list(), e.row(), data.get(e.row()).line(),
                        conversionErrors.getOrDefault(e.row(), e.error())))
                .toList();
        return new Result(report, errors);
    }

    private static List<String> columns(List<String> header, Set<String> fields, List<AttributeDefinition> defined) {
        Map<String, String> byName = new HashMap<>();
        HEADERS.forEach((field, japanese) -> {
            byName.put(field.toLowerCase(Locale.ROOT), field);
            byName.put(japanese, field);
        });
        List<String> columns = new ArrayList<>();
        for (String cell : header) {
            String name = cell.strip();
            if (name.startsWith(ATTRIBUTE_COLUMN)) {
                String attribute = name.substring(ATTRIBUTE_COLUMN.length());
                if (defined.stream().noneMatch(definition -> definition.name().equals(attribute))
                        || columns.contains(name)) {
                    throw FuhitoException.invalid("csv.bad_column", Map.of("column", name));
                }
                columns.add(name);
                continue;
            }
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

    private RefKindRow refKind(Cells cells) {
        String code = cells.text("code", null);
        Optional<RealmKindService.View> stored = code == null ? Optional.empty()
                : importService.setup().kinds().find(code);
        return new RefKindRow(code, cells.text("name", stored.map(RealmKindService.View::name).orElse(null)),
                cells.localized("name", stored.map(RealmKindService.View::names).orElse(Map.of())));
    }

    // Blank cells keep the stored values. A user named by username replaces the stored ID of the user.
    private ChangeOrderRow changeOrder(Cells cells) {
        String id = cells.text("id", null);
        String number = cells.text("number", null);
        ChangeOrderService service = importService.records().changeOrders();
        Optional<ChangeOrder> stored = id != null ? service.find(id)
                : number == null ? Optional.empty() : service.ofNumber(number);
        String approverUsername = cells.text("approverUsername", null);
        String requesterUsername = cells.text("requesterUsername", null);
        return new ChangeOrderRow(id, cells.text("number", stored.map(ChangeOrder::number).orElse(null)),
                cells.text("kind", stored.map(ChangeOrder::kind).orElse(null)),
                cells.text("title", stored.map(ChangeOrder::title).orElse(null)),
                cells.text("note", stored.map(ChangeOrder::note).orElse(null)),
                cells.text("orderDate", stored.map(o -> Objects.toString(o.orderDate(), null)).orElse(null)),
                cells.text("effectiveDate", stored.map(o -> o.effectiveDate().toString()).orElse(null)),
                cells.text("approverUserId", approverUsername == null
                        ? stored.map(ChangeOrder::approverUserId).orElse(null) : null), approverUsername,
                cells.text("requesterUserId", requesterUsername == null
                        ? stored.map(ChangeOrder::requesterUserId).orElse(null) : null), requesterUsername,
                cells.text("externalReference", stored.map(ChangeOrder::externalReference).orElse(null)));
    }

    // A blank cell keeps the stored ID, and the cell #CLEAR removes it.
    private ExternalIdRow externalId(Cells cells, String defaultSource) {
        String kind = cells.text("resourceType", null);
        String resourceId = cells.text("resourceId", null);
        String source = cells.text("source", null);
        String value = cells.clearable("externalId");
        if (value == null && kind != null && resourceId != null) {
            String system = source == null ? defaultSource : source;
            value = quietly(() -> importService.records().externalIds().list(kind, resourceId)).stream()
                    .flatMap(List::stream)
                    .filter(view -> !view.own() && view.source().equals(system))
                    .map(ExternalIdService.View::externalId)
                    .findFirst()
                    .orElse(null);
        }
        return new ExternalIdRow(kind, resourceId, source, value);
    }

    // Blank cells keep the stored values, as the row replaces every value of a stored qualification.
    private QualificationRow qualification(Cells cells, String defaultSource) {
        String id = cells.text("id", null);
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        QualificationService service = importService.records().qualifications();
        Optional<QualificationView> stored = id != null ? service.find(id)
                : externalId == null ? Optional.empty()
                : service.ofExternalId(source == null ? defaultSource : source, externalId);
        Optional<Qualification> q = stored.map(QualificationView::qualification);
        return new QualificationRow(id, source, externalId, cells.text("personExternalId", null),
                cells.text("personId", null), cells.text("username", null),
                cells.text("kind", stored.map(QualificationView::kind).orElse(null)),
                cells.text("number", q.map(Qualification::number).orElse(null)),
                cells.text("validFrom", q.map(v -> v.period().from().toString()).orElse(null)),
                cells.text("validTo", q.map(v -> Objects.toString(v.period().to(), null)).orElse(null)),
                cells.attributes(q.map(Qualification::attributes).orElse(null)));
    }

    // A row with an ID keeps the stored mark in a blank cell.
    private UnitRoleRow unitRole(Cells cells) {
        String id = cells.text("id", null);
        Optional<UnitRoleService.View> stored = id == null ? Optional.empty()
                : importService.records().unitRoles().find(id);
        return new UnitRoleRow(id, cells.text("source", null), cells.text("employmentExternalId", null),
                cells.text("employmentId", null), cells.text("orgUnitExternalId", null), cells.text("orgUnitId", null),
                cells.text("role", null),
                cells.bool("includesDescendants", stored.map(view -> view.role().includesDescendants()).orElse(null)),
                cells.text("validFrom", null), cells.text("validTo", null));
    }

    // A row with an ID keeps the stored assignment, manager, and kind in blank cells.
    private ReportingLineRow reportingLine(Cells cells) {
        return new ReportingLineRow(cells.text("id", null), cells.text("source", null),
                cells.text("assignmentExternalId", null), cells.text("assignmentId", null),
                cells.text("managerPersonExternalId", null), cells.text("managerPersonId", null),
                cells.text("managerUsername", null), cells.text("kind", null), cells.text("validFrom", null),
                cells.text("validTo", null));
    }

    // Blank cells of the parent keep the stored parent. The cell #CLEAR takes the unit out of the hierarchy.
    private OrgUnitParentRow orgUnitParent(Cells cells, LocalDate date, String defaultSource) {
        String hierarchy = cells.text("hierarchy", null);
        String source = cells.text("source", null);
        String externalId = cells.text("orgUnitExternalId", null);
        String id = cells.text("orgUnitId", null);
        String parentId = cells.text("parentId", null);
        if (cells.blank("parentExternalId") && cells.blank("parentId") && hierarchy != null) {
            Optional<String> unit = id != null ? Optional.of(id) : externalId == null ? Optional.empty()
                    : orgUnits.findUnitByExternalId(source == null ? defaultSource : source, externalId)
                            .map(OrgUnit::id);
            parentId = unit.flatMap(u -> quietly(() -> orgUnitService.get(u, date.toString(), hierarchy)))
                    .map(OrgUnitState::parentId)
                    .orElse(null);
        }
        return new OrgUnitParentRow(hierarchy, source, externalId, id, cells.text("parentExternalId", null),
                parentId);
    }

    private HierarchyRow hierarchy(Cells cells) {
        String code = cells.text("code", null);
        Optional<Hierarchy> stored = code == null ? Optional.empty() : importService.setup().hierarchies().find(code);
        return new HierarchyRow(code, cells.text("name", stored.map(Hierarchy::name).orElse(null)));
    }

    private AttributeDefinitionRow attributeDefinition(Cells cells) {
        String target = cells.text("target", null);
        String name = cells.text("name", null);
        // The import reports an unknown target. Here it only means that nothing is stored.
        Optional<AttributeDefinition> stored = target == null || name == null ? Optional.empty()
                : quietly(() -> importService.setup().definitions().list(target)).stream()
                        .flatMap(List::stream)
                        .filter(definition -> definition.name().equals(name))
                        .findFirst();
        return new AttributeDefinitionRow(target, name,
                cells.text("type", stored.map(definition -> definition.type().name()).orElse(null)),
                cells.text("refKind", stored.map(AttributeDefinition::refKind).orElse(null)),
                cells.bool("multiple", stored.map(AttributeDefinition::multiple).orElse(null)),
                cells.bool("required", stored.map(AttributeDefinition::required).orElse(null)));
    }

    private RefItemRow refItem(Cells cells) {
        String kind = cells.text("kind", null);
        String code = cells.text("code", null);
        // The import reports an unknown kind. Here it only means that nothing is stored.
        Optional<RefItem> stored = code == null || kind == null ? Optional.empty() : refItems.findByCode(kind, code);
        return new RefItemRow(kind, code, cells.text("name", stored.map(RefItem::name).orElse(null)),
                cells.localized("name", stored.map(RefItem::names).orElse(Map.of())),
                cells.integer("displayOrder", stored.map(RefItem::displayOrder).orElse(null)),
                cells.attributes(stored.map(RefItem::attributes).orElse(null)),
                cells.bool("active", stored.map(RefItem::active).orElse(null)),
                cells.bool("legalEntity", null), cells.bool("actingHead", null), cells.bool("lateral", null));
    }

    private OrgUnitRow orgUnit(Cells cells, LocalDate date, String defaultSource) {
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        String id = cells.text("id", null);
        Optional<OrgUnitState> stored = Optional.empty();
        if (id != null) {
            stored = quietly(() -> orgUnitService.get(id, date.toString()));
        } else if (externalId != null) {
            stored = orgUnits.findUnitByExternalId(source == null ? defaultSource : source, externalId)
                    .flatMap(unit -> quietly(() -> orgUnitService.get(unit.id(), date.toString())));
        }
        var version = stored.map(OrgUnitState::version);
        String rowSource = source == null ? defaultSource : source;
        Optional<OrgUnit> parent = stored.map(OrgUnitState::parentId).flatMap(orgUnits::findUnit);
        // A blank cell keeps the stored parent. One with no external ID of the source is kept by its ID.
        String storedParent = parent.filter(unit -> Objects.equals(rowSource, unit.source())).map(OrgUnit::externalId)
                .orElse(null);
        String keptParentId = storedParent == null && cells.blank("parentExternalId")
                ? parent.map(OrgUnit::id).orElse(null) : null;
        // A cell of the parent ID names the parent, and the cell #CLEAR makes a root. A CSV export has it.
        String parentExternalId = cells.text("parentExternalId", storedParent);
        if (!cells.blank("parentId")) {
            parentExternalId = cells.text("parentExternalId", null);
            keptParentId = cells.text("parentId", null);
        }
        return new OrgUnitRow(source, externalId, cells.text("code", version.map(v -> v.code()).orElse(null)),
                cells.text("name", version.map(v -> v.name()).orElse(null)),
                cells.localized("name", version.map(v -> v.names()).orElse(Map.of())),
                cells.text("shortName", version.map(v -> v.shortName()).orElse(null)),
                cells.localized("shortName", version.map(v -> v.shortNames()).orElse(Map.of())),
                cells.text("phoneticName", version.map(v -> v.phoneticName()).orElse(null)),
                cells.text("unitType", stored.map(OrgUnitState::unitType).orElse(null)),
                parentExternalId, keptParentId,
                cells.integer("displayOrder", version.map(v -> v.displayOrder()).orElse(null)),
                cells.attributes(version.map(v -> v.attributes()).orElse(null)),
                cells.text("validFrom", null), cells.text("validTo", null), id,
                cells.list("successorExternalIds"), successorIds(cells, stored), cells.clearable("organizationAlias"),
                cells.clearable("timeZone"), cells.clearable("dayStart"));
    }

    // Blank cells of the successors keep the stored ones of a row with an end.
    private List<String> successorIds(Cells cells, Optional<OrgUnitState> stored) {
        if (!cells.blank("successorExternalIds") || !cells.blank("successorIds") || cells.blank("validTo")) {
            return cells.list("successorIds");
        }
        List<String> successors = stored.map(state -> orgUnitService.successions(state.unit().id()).successors()
                .stream().map(OrgUnitSuccession::successorId).toList()).orElse(List.of());
        return successors.isEmpty() ? null : successors;
    }

    private PersonRow person(Cells cells, String defaultSource) {
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        String id = cells.text("id", null);
        Optional<Person> stored = id != null ? people.personService().find(id)
                : externalId == null ? Optional.empty()
                : people.personService().ofExternalId(source == null ? defaultSource : source, externalId);
        return new PersonRow(source, externalId, cells.text("username", null), cells.text("userId", null),
                cells.text("name", stored.map(Person::name).orElse(null)),
                cells.localized("name", stored.map(Person::names).orElse(Map.of())),
                cells.text("phoneticName", stored.map(Person::phoneticName).orElse(null)),
                cells.attributes(stored.map(Person::attributes).orElse(null)), id);
    }

    private EmploymentRow employment(Cells cells, LocalDate date, String defaultSource) {
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        String id = cells.text("id", null);
        Optional<Employment> employment = id != null ? people.employments().find(id)
                : externalId == null ? Optional.empty()
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
        String status = cells.text("status", terms.map(t -> t.terms().status().name()).orElse(null));
        // A new status that is not a leave drops the stored reason, unless the row names one.
        String leaveReason = cells.text("leaveReason", EmploymentStatus.ON_LEAVE.name().equals(status)
                ? terms.map(EmploymentView.TermsView::leaveReason).orElse(null) : null);
        return new EmploymentRow(source, externalId, username, userId, cells.text("staffNumber", null),
                cells.text("employmentType", terms.map(EmploymentView.TermsView::employmentType).orElse(null)),
                cells.text("grade", terms.map(EmploymentView.TermsView::grade).orElse(null)), status, leaveReason,
                cells.decimal("workPercent", terms.map(t -> t.terms().workPercent()).orElse(null)),
                cells.text("contractEndDate", terms.map(t -> t.terms().contractEndDate())
                        .map(LocalDate::toString).orElse(null)),
                cells.attributes(terms.map(t -> t.terms().attributes()).orElse(null)),
                cells.text("validFrom", null), cells.text("validTo", null), cells.text("endReason", null),
                cells.text("employerExternalId", null), cells.text("personExternalId", null),
                cells.text("personId", null), cells.text("employerId", null), id,
                cells.text("continuesEmploymentExternalId", null), cells.text("continuesEmploymentId", null));
    }

    private AssignmentRow assignment(Cells cells, LocalDate date, String defaultSource) {
        String source = cells.text("source", null);
        String externalId = cells.text("externalId", null);
        String id = cells.text("id", null);
        Optional<String> stored = id != null ? Optional.of(id).filter(i -> !people.assignments().rows(i).isEmpty())
                : externalId == null ? Optional.empty()
                : people.assignments().findAssignmentIdByExternalId(source == null ? defaultSource : source,
                        externalId);
        Optional<AssignmentView.Row> current = stored.map(people.assignmentService()::get)
                .flatMap(v -> v.rows().stream().filter(r -> r.row().period().contains(date)).findFirst());
        Optional<Assignment> row = current.map(AssignmentView.Row::row);
        // A row for a stored assignment may leave out the employment and the org unit. The import keeps them.
        return new AssignmentRow(source, externalId, cells.text("employmentExternalId", null),
                cells.text("orgUnitExternalId", null),
                cells.text("jobTitle", current.map(AssignmentView.Row::jobTitle).orElse(null)),
                cells.text("occupation", current.map(AssignmentView.Row::occupation).orElse(null)),
                cells.text("assignmentKind", current.map(AssignmentView.Row::kind).orElse(null)),
                cells.bool("head", row.map(Assignment::head).orElse(null)),
                cells.bool("primary", row.map(Assignment::primary).orElse(null)),
                cells.decimal("allocationPercent", row.map(Assignment::allocationPercent).orElse(null)),
                cells.integer("displayOrder", row.map(Assignment::displayOrder).orElse(null)),
                cells.attributes(row.map(Assignment::attributes).orElse(null)),
                cells.text("validFrom", null), cells.text("validTo", null), cells.text("employmentId", null),
                cells.text("orgUnitId", null), id, cells.text("continuesAssignmentExternalId", null),
                cells.text("continuesAssignmentId", null));
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
    private record Cells(Map<String, String> values, List<AttributeDefinition> defined) {

        static Cells of(List<String> columns, List<String> cells, List<AttributeDefinition> defined) {
            // A comma that should have been in quotes moves every later value one column to the right.
            if (cells.subList(Math.min(columns.size(), cells.size()), cells.size()).stream()
                    .anyMatch(cell -> !cell.isBlank())) {
                throw FuhitoException.invalid("csv.too_many_cells",
                        Map.of("columns", Integer.toString(columns.size()), "cells", Integer.toString(cells.size())));
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                values.put(columns.get(i), i < cells.size() ? cells.get(i).strip() : "");
            }
            return new Cells(values, defined);
        }

        // A column of a defined attribute sets it. The other attributes keep what is stored.
        Map<String, Object> attributes(Map<String, Object> stored) {
            if (defined.stream().noneMatch(definition -> values.containsKey(ATTRIBUTE_COLUMN + definition.name()))) {
                return stored;
            }
            Map<String, Object> result = stored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored);
            for (AttributeDefinition definition : defined) {
                String column = ATTRIBUTE_COLUMN + definition.name();
                Object value;
                if (definition.multiple()) {
                    value = array(column);
                } else {
                    value = switch (definition.type()) {
                        case NUMBER -> asJson(decimal(column, null));
                        case BOOLEAN -> bool(column, null);
                        default -> text(column, null);
                    };
                }
                if (value != null) {
                    result.put(definition.name(), value);
                } else if (values.getOrDefault(column, "").equals(CLEAR)) {
                    result.remove(definition.name());
                }
            }
            return result;
        }

        // A stored attribute reads back from JSON as Integer, Long, BigInteger, or Double. A value of the same
        // type compares equal to it, so a row that matches what is stored changes nothing.
        private static Object asJson(BigDecimal value) {
            if (value == null) {
                return null;
            }
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() > 0) {
                return value.doubleValue();
            }
            BigInteger whole = stripped.toBigIntegerExact();
            if (whole.bitLength() < Integer.SIZE) {
                return whole.intValue();
            }
            return whole.bitLength() < Long.SIZE ? (Object) whole.longValue() : whole;
        }

        // The definition checks the elements later, as it does for a JSON import.
        private List<Object> array(String field) {
            String value = text(field, null);
            if (value == null) {
                return null;
            }
            try {
                return JSON.readValue(value, new TypeReference<>() { });
            } catch (JsonProcessingException e) {
                throw FuhitoException.invalid("csv.bad_list", Map.of("field", field, "value", value)).causedBy(e);
            }
        }

        // A blank cell keeps the stored value, and the cell #CLEAR gives an empty text, which removes it.
        String clearable(String field) {
            String value = values.getOrDefault(field, "");
            if (value.isEmpty()) {
                return null;
            }
            return value.equals(CLEAR) ? "" : value;
        }

        // Several values in one cell are separated by a vertical bar.
        List<String> list(String field) {
            String value = values.getOrDefault(field, "");
            if (value.isEmpty()) {
                return null;
            }
            return value.equals(CLEAR) ? List.of() : Arrays.stream(value.split("\\|"))
                    .map(String::strip)
                    .filter(part -> !part.isEmpty())
                    .toList();
        }

        boolean blank(String field) {
            return values.getOrDefault(field, "").isEmpty();
        }

        String text(String field, String stored) {
            String value = values.getOrDefault(field, "");
            if (value.isEmpty()) {
                return stored;
            }
            return value.equals(CLEAR) ? null : value;
        }

        Integer integer(String field, Integer stored) {
            return parsed(field, stored, Integer::valueOf);
        }

        BigDecimal decimal(String field, BigDecimal stored) {
            return parsed(field, stored, BigDecimal::new);
        }

        Boolean bool(String field, Boolean stored) {
            return parsed(field, stored, value -> {
                String lower = value.toLowerCase(Locale.ROOT);
                if (TRUE.contains(lower)) {
                    return true;
                }
                if (FALSE.contains(lower)) {
                    return false;
                }
                throw FuhitoException.invalid("csv.bad_boolean", Map.of("field", field, "value", value));
            });
        }

        private <T> T parsed(String field, T stored, Function<String, T> parse) {
            String value = text(field, null);
            if (value == null) {
                return values.getOrDefault(field, "").equals(CLEAR) ? null : stored;
            }
            try {
                return parse.apply(value);
            } catch (NumberFormatException e) {
                throw FuhitoException.invalid("csv.bad_number", Map.of("field", field, "value", value)).causedBy(e);
            }
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
