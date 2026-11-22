package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Csv;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.JsonNumbers;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.NumberText;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKindName;
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
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Turns one CSV file into an import document and applies it. A cell is read as it is, with the spaces around it.
 * An empty cell is a key that a JSON row leaves out, so it keeps the stored value. The cell {@code #CLEAR} is null,
 * which removes it.
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
            Map.entry("lateral", "横断"), Map.entry("rollup", "集計"), Map.entry("personExternalId", "人の外部ID"),
            Map.entry("head", "長"), Map.entry("primary", "主務"), Map.entry("allocationPercent", "配分割合"),
            Map.entry("id", "ID"), Map.entry("parentId", "親のID"), Map.entry("personId", "人のID"),
            Map.entry("employerId", "雇用主のID"), Map.entry("employmentId", "雇用のID"),
            Map.entry("orgUnitId", "組織のID"), Map.entry("target", "対象"), Map.entry("type", "型"),
            Map.entry("refKind", "参照の種類"), Map.entry("multiple", "複数"), Map.entry("required", "必須"),
            Map.entry("fillValue", "値のない行に入れる値"),
            Map.entry("hierarchy", "階層"), Map.entry("workLocation", "勤務地"), Map.entry("costCenter", "コストセンター"),
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
            Map.entry("dayStart", "日の始まり"), Map.entry("primaryLanguage", "主言語"),
            Map.entry("continuesEmploymentExternalId", "引き継ぎ元の雇用の外部ID"),
            Map.entry("continuesEmploymentId", "引き継ぎ元の雇用のID"),
            Map.entry("externalEmployer", "外部の雇用主"));

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
            Map.entry(Kind.REF_KINDS, Set.of("id", "externalId", "source", "code", "primaryLanguage", "name")),
            Map.entry(Kind.ATTRIBUTE_DEFINITIONS, Set.of("id", "externalId", "source", "target", "name", "type",
                    "refKind", "multiple", "required", "fillValue")),
            Map.entry(Kind.HIERARCHIES, Set.of("id", "externalId", "source", "code", "name")),
            Map.entry(Kind.ORG_UNIT_PARENTS, Set.of("hierarchy", "source", "orgUnitExternalId", "orgUnitId",
                    "parentExternalId", "parentId")),
            Map.entry(Kind.EXTERNAL_IDS, Set.of("resourceType", "resourceId", "source", "externalId")),
            Map.entry(Kind.CHANGE_ORDERS, Set.of("id", "externalId", "source", "number", "kind", "title", "note",
                    "orderDate", "effectiveDate", "approverUserId", "approverUsername", "requesterUserId",
                    "requesterUsername", "externalReference")),
            Map.entry(Kind.QUALIFICATIONS, Set.of("id", "source", "externalId", "personExternalId", "personId",
                    "username", "kind", "number", "validFrom", "validTo")),
            Map.entry(Kind.UNIT_ROLES, Set.of("id", "externalId", "source", "employmentExternalId", "employmentId",
                    "orgUnitExternalId", "orgUnitId", "role", "includesDescendants", "validFrom", "validTo")),
            Map.entry(Kind.REPORTING_LINES, Set.of("id", "externalId", "source", "assignmentExternalId",
                    "assignmentId", "managerPersonExternalId", "managerPersonId", "managerUsername", "kind",
                    "validFrom", "validTo")),
            Map.entry(Kind.REF_ITEMS, Set.of("id", "externalId", "source", "kind", "code", "primaryLanguage", "name",
                    "displayOrder", "active", "legalEntity", "actingHead", "lateral", "rollup", "validFrom")),
            Map.entry(Kind.ORG_UNITS, Set.of("id", "externalId", "source", "code", "primaryLanguage", "name",
                    "shortName", "phoneticName", "unitType", "workLocation", "costCenter", "parentExternalId",
                    "parentId", "displayOrder", "validFrom", "validTo", "successorExternalIds", "successorIds",
                    "organizationAlias", "timeZone", "dayStart")),
            Map.entry(Kind.EMPLOYMENTS, Set.of("id", "externalId", "source", "username", "userId", "staffNumber",
                    "employmentType", "grade", "status", "leaveReason", "workPercent", "contractEndDate", "validFrom",
                    "validTo", "endReason", "employerExternalId", "employerId", "externalEmployer",
                    "personExternalId", "personId", "continuesEmploymentExternalId", "continuesEmploymentId")),
            Map.entry(Kind.PEOPLE, Set.of("id", "externalId", "source", "username", "userId", "primaryLanguage",
                    "name", "phoneticName")),
            Map.entry(Kind.ASSIGNMENTS, Set.of("id", "externalId", "source", "employmentExternalId", "employmentId",
                    "orgUnitExternalId", "orgUnitId", "jobTitle", "occupation", "assignmentKind", "workLocation",
                    "costCenter", "head", "primary", "allocationPercent", "displayOrder", "validFrom", "validTo")));

    private static final Set<String> LOCALIZED = Set.of("name");
    private static final List<String> UNIT_NAMES = List.of("name", "shortName", "phoneticName");
    private static final List<String> PERSON_NAMES = List.of("name", "phoneticName");
    private static final List<String> NAME_ONLY = List.of("name");
    static final String ATTRIBUTE_COLUMN = "attributes.";
    private static final Set<String> TRUE = Set.of("true", "1", "yes", "y", "はい", "○", "〇");
    private static final Set<String> FALSE = Set.of("false", "0", "no", "n", "いいえ", "×");
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n?|\n");
    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    private final Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions;
    private final ImportService importService;
    private final RefItemStore refItems;
    private final OrgUnitService orgUnitService;
    private final ImportService.People people;

    /**
     * Creates the service. A file can set the defined attributes of the realm in columns of their own.
     *
     * @param importService the import that writes the rows
     * @param refItems the reference items of the realm
     * @param orgUnitService the operations on org units of the realm
     * @param people the stores and services of people of the realm
     * @param definitions the definitions of attributes, by kind of resource
     */
    public CsvImportService(ImportService importService, RefItemStore refItems, OrgUnitService orgUnitService,
            ImportService.People people,
            Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions) {
        this.definitions = definitions;
        this.importService = importService;
        this.refItems = refItems;
        this.orgUnitService = orgUnitService;
        this.people = people;
    }

    /**
     * Reads the bytes of a file as text in its charset. fuhito does not guess the encoding.
     * A byte order mark of UTF-8 stays at the start of the text.
     *
     * @param file the bytes of the file
     * @param charset the name of the charset of the file, or null for UTF-8
     * @return the text
     * @throws FuhitoException with {@code csv.bad_charset} when the charset is unknown, and with
     *         {@code csv.bad_encoding} when the bytes are not valid in the charset
     */
    public static String decode(byte[] file, String charset) {
        Charset decoding = charset == null ? StandardCharsets.UTF_8 : charsetNamed(charset);
        if (asciiCompatible(decoding)) {
            rejectUtf16(file, decoding);
        }
        ByteBuffer in = ByteBuffer.wrap(file);
        try {
            return decoding.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(in)
                    .toString();
        } catch (CharacterCodingException e) {
            int length = switch (e) {
                case MalformedInputException malformed -> malformed.getInputLength();
                case UnmappableCharacterException unmappable -> unmappable.getInputLength();
                default -> 1;
            };
            throw badEncoding(file, decoding, in.position(), length).causedBy(e);
        }
    }

    private static Charset charsetNamed(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw FuhitoException.invalid("csv.bad_charset", Map.of("charset", name)).causedBy(e);
        }
    }

    private static boolean asciiCompatible(Charset charset) {
        return charset.canEncode() && Arrays.equals(",\n".getBytes(charset), new byte[] {',', '\n'});
    }

    // A UTF-16 file of ASCII text has 00 bytes, which such a charset reads as NUL with no error.
    private static void rejectUtf16(byte[] file, Charset charset) {
        int start = file.length < 2 ? 0 : ((file[0] & 0xFF) << 8) | (file[1] & 0xFF);
        if (start == 0xFEFF || start == 0xFFFE) {
            throw badEncoding(file, charset, 0, 2);
        }
        for (int i = 0; i < file.length && file[i] != '\n'; i++) {
            if (file[i] == 0) {
                throw badEncoding(file, charset, i, 1);
            }
        }
    }

    private static FuhitoException badEncoding(byte[] file, Charset charset, int offset, int length) {
        String before = new String(file, 0, offset, charset);
        return FuhitoException.invalid("csv.bad_encoding", Map.of(
                "charset", charset.name(),
                "line", Integer.toString(LINE_BREAK.split(before, -1).length),
                "offset", Integer.toString(offset),
                "bytes", HEX.formatHex(file, offset, Math.min(file.length, offset + length))));
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
        Set<String> localized = switch (kind) {
            case ORG_UNITS -> Set.copyOf(UNIT_NAMES);
            case PEOPLE -> Set.copyOf(PERSON_NAMES);
            default -> LOCALIZED;
        };
        Map<String, String> columns = columns(rows.getFirst().cells(), FIELDS.get(kind), localized, defined);
        List<Csv.Row> data = rows.subList(1, rows.size());

        // Each list has its own type of row, so the rows keep that type from the cells to the import.
        Function<List<?>, ImportDocument> document = typed -> ImportDocument.of(effectiveDate, dryRun,
                defaultSource, kind.list, typed);
        return switch (kind) {
            case REF_KINDS -> run(data, columns, defined, cells -> refKind(cells, defaultSource), document);
            case ATTRIBUTE_DEFINITIONS -> run(data, columns, defined, cells -> attributeDefinition(cells, defaultSource),
                    document);
            case HIERARCHIES -> run(data, columns, defined, this::hierarchy, document);
            case ORG_UNIT_PARENTS -> run(data, columns, defined, this::orgUnitParent, document);
            case REPORTING_LINES -> run(data, columns, defined, this::reportingLine, document);
            case UNIT_ROLES -> run(data, columns, defined, this::unitRole, document);
            case QUALIFICATIONS -> run(data, columns, defined, this::qualification, document);
            case EXTERNAL_IDS -> run(data, columns, defined, this::externalId, document);
            case CHANGE_ORDERS -> run(data, columns, defined, this::changeOrder, document);
            case REF_ITEMS -> run(data, columns, defined, cells -> refItem(cells, date, defaultSource), document);
            case ORG_UNITS -> run(data, columns, defined, cells -> orgUnit(cells, date, defaultSource), document);
            case EMPLOYMENTS -> run(data, columns, defined, this::employment, document);
            case ASSIGNMENTS -> run(data, columns, defined, this::assignment, document);
            case PEOPLE -> run(data, columns, defined, cells -> person(cells, defaultSource), document);
        };
    }

    private <R> Result run(List<Csv.Row> data, Map<String, String> columns, List<AttributeDefinition> defined,
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

    // The header of each column, as the file writes it, by the column.
    private static Map<String, String> columns(List<String> header, Set<String> fields, Set<String> localizedFields,
            List<AttributeDefinition> defined) {
        Map<String, String> byName = new HashMap<>();
        HEADERS.forEach((field, japanese) -> {
            byName.put(field.toLowerCase(Locale.ROOT), field);
            byName.put(japanese, field);
        });
        Map<String, String> columns = new LinkedHashMap<>();
        Set<String> normalizedColumns = new HashSet<>();
        for (String name : header) {
            if (name.strip().length() != name.length()) {
                throw FuhitoException.invalid("csv.bad_column", Map.of("column", name));
            }
            if (name.startsWith(ATTRIBUTE_COLUMN)) {
                String attribute = name.substring(ATTRIBUTE_COLUMN.length());
                if (defined.stream().noneMatch(definition -> definition.name().equals(attribute))
                        || columns.containsKey(name)) {
                    throw FuhitoException.invalid("csv.bad_column", Map.of("column", name));
                }
                columns.put(name, name);
                continue;
            }
            int colon = name.indexOf(':');
            String base = colon < 0 ? name : name.substring(0, colon);
            String field = byName.get(base);
            if (field == null) {
                field = byName.get(base.toLowerCase(Locale.ROOT));
            }
            boolean localized = colon >= 0;
            String column = localized ? field + name.substring(colon) : field;
            if (field == null || !fields.contains(field) || (localized && !localizedFields.contains(field))
                    || columns.containsKey(column)) {
                throw FuhitoException.invalid("csv.bad_column", Map.of("column", name));
            }
            if (localized) {
                String language = language(name.substring(colon + 1));
                if (!normalizedColumns.add(field + ":" + language)) {
                    throw FuhitoException.invalid("invalid.duplicate_language",
                            Map.of("field", name, "value", language));
                }
            }
            columns.put(column, name);
        }
        return columns;
    }

    // A bad tag fails in each row that fills its column.
    private static String language(String tag) {
        try {
            return Checks.languageTag(tag, tag);
        } catch (FuhitoException e) {
            return tag;
        }
    }

    // The stored thing that a row names, as the import finds it. The import reports a row that names nothing. A key
    // that a write never stores fails here with its own error, and not with an error about what the row lacks.
    private Optional<String> storedId(ExternalId.Kind kind, Cells cells, String defaultSource) {
        String id = cells.text("id");
        String externalId = cells.text("externalId");
        if (id != null || externalId == null) {
            return Optional.ofNullable(id);
        }
        return resourceOf(kind, Checks.optionalCode("source", cells.text("source")),
                ExternalIdService.value(externalId), defaultSource);
    }

    private RefKindRow refKind(Cells cells, String defaultSource) {
        RealmKindService kinds = importService.setup().kinds();
        String primaryLanguage = cells.primaryLanguage(() -> storedId(ExternalId.Kind.REF_KIND, cells, defaultSource)
                .flatMap(kinds::codeOf)
                .flatMap(kinds::find)
                .map(view -> view.names().primaryLanguage()));
        return new RefKindRow(cells.text("id"), cells.text("source"), cells.text("externalId"), cells.text("code"),
                cells.text("primaryLanguage"), cells.names(primaryLanguage, NAME_ONLY,
                        (name, field, text) -> new RefKindName(text)), cells.nulls());
    }

    private ChangeOrderRow changeOrder(Cells cells) {
        return new ChangeOrderRow(cells.text("id"), cells.text("source"), cells.text("externalId"),
                cells.text("number"), cells.text("kind"), cells.text("title"), cells.text("note"),
                cells.text("orderDate"), cells.text("effectiveDate"), cells.text("approverUserId"),
                cells.text("approverUsername"), cells.text("requesterUserId"), cells.text("requesterUsername"),
                cells.text("externalReference"), cells.nulls());
    }

    private ExternalIdRow externalId(Cells cells) {
        return new ExternalIdRow(cells.text("resourceType"), cells.text("resourceId"), cells.text("source"),
                cells.text("externalId"), cells.nulls());
    }

    private QualificationRow qualification(Cells cells) {
        return new QualificationRow(cells.text("id"), cells.text("source"), cells.text("externalId"),
                cells.text("personExternalId"), cells.text("personId"), cells.text("username"), cells.text("kind"),
                cells.text("number"), cells.text("validFrom"), cells.text("validTo"), cells.attributes(),
                cells.nulls());
    }

    private UnitRoleRow unitRole(Cells cells) {
        return new UnitRoleRow(cells.text("id"), cells.text("source"), cells.text("externalId"),
                cells.text("employmentExternalId"), cells.text("employmentId"), cells.text("orgUnitExternalId"),
                cells.text("orgUnitId"), cells.text("role"), cells.bool("includesDescendants"),
                cells.text("validFrom"), cells.text("validTo"), cells.nulls());
    }

    private ReportingLineRow reportingLine(Cells cells) {
        return new ReportingLineRow(cells.text("id"), cells.text("source"), cells.text("externalId"),
                cells.text("assignmentExternalId"), cells.text("assignmentId"), cells.text("managerPersonExternalId"),
                cells.text("managerPersonId"), cells.text("managerUsername"), cells.text("kind"),
                cells.text("validFrom"), cells.text("validTo"), cells.nulls());
    }

    private OrgUnitParentRow orgUnitParent(Cells cells) {
        return new OrgUnitParentRow(cells.text("hierarchy"), cells.text("source"), cells.text("orgUnitExternalId"),
                cells.text("orgUnitId"), cells.text("parentExternalId"), cells.text("parentId"), cells.nulls());
    }

    private HierarchyRow hierarchy(Cells cells) {
        return new HierarchyRow(cells.text("id"), cells.text("source"), cells.text("externalId"), cells.text("code"),
                cells.text("name"), cells.nulls());
    }

    // The cell of the value to fill is read by the type of the definition. A row of a stored definition that leaves
    // out the type or multiple has the stored one.
    private AttributeDefinitionRow attributeDefinition(Cells cells, String defaultSource) {
        String type = cells.text("type");
        Boolean multiple = cells.bool("multiple");
        Object fillValue = null;
        if (cells.text("fillValue") != null) {
            Optional<AttributeDefinition> stored = storedId(ExternalId.Kind.ATTRIBUTE_DEFINITION, cells, defaultSource)
                    .flatMap(id -> importService.setup().definitions().find(id));
            fillValue = cells.value("fillValue", type == null ? stored.map(d -> d.type().name()).orElse(null) : type,
                    multiple == null ? stored.map(AttributeDefinition::multiple).orElse(false) : multiple);
        }
        return new AttributeDefinitionRow(cells.text("id"), cells.text("source"), cells.text("externalId"),
                cells.text("target"), cells.text("name"), type, cells.text("refKind"), multiple,
                cells.bool("required"), fillValue, cells.nulls());
    }

    // The cells of the primary names need the primary language of the names of the date of the row.
    private RefItemRow refItem(Cells cells, LocalDate effectiveDate, String defaultSource) {
        String validFrom = cells.text("validFrom");
        String primaryLanguage = cells.primaryLanguage(() -> {
            LocalDate date = validFrom == null ? effectiveDate : Checks.date("validFrom", validFrom);
            return storedId(ExternalId.Kind.REF_ITEM, cells, defaultSource)
                    .flatMap(id -> refItems.find(id, date))
                    .map(item -> item.names().primaryLanguage());
        });
        return new RefItemRow(cells.text("id"), cells.text("source"), cells.text("externalId"), cells.text("kind"),
                cells.text("code"), cells.text("primaryLanguage"), cells.names(primaryLanguage, NAME_ONLY,
                        (name, field, text) -> new RefItemName(text)), cells.integer("displayOrder"),
                cells.attributes(), cells.bool("active"), cells.bool("legalEntity"), cells.bool("actingHead"),
                cells.bool("lateral"), cells.bool("rollup"), validFrom, cells.nulls());
    }

    private OrgUnitRow orgUnit(Cells cells, LocalDate date, String defaultSource) {
        String primaryLanguage = cells.primaryLanguage(() -> storedId(ExternalId.Kind.ORG_UNIT, cells, defaultSource)
                .flatMap(unit -> quietly(() -> orgUnitService.get(unit, date.toString())))
                .map(state -> state.version().names().primaryLanguage()));
        return new OrgUnitRow(cells.text("source"), cells.text("externalId"), cells.text("code"),
                cells.text("primaryLanguage"), cells.names(primaryLanguage, UNIT_NAMES, CsvImportService::withUnitName),
                cells.text("unitType"), cells.text("parentExternalId"), cells.text("parentId"),
                cells.integer("displayOrder"), cells.attributes(), cells.text("validFrom"), cells.text("validTo"),
                cells.text("id"), cells.list("successorExternalIds"), cells.list("successorIds"),
                cells.text("organizationAlias"), cells.text("timeZone"), cells.text("dayStart"),
                cells.text("workLocation"), cells.text("costCenter"), null, cells.nulls());
    }

    private PersonRow person(Cells cells, String defaultSource) {
        String primaryLanguage = cells.primaryLanguage(() -> storedId(ExternalId.Kind.PERSON, cells, defaultSource)
                .flatMap(people.personService()::find)
                .map(Person::names)
                .map(Localized::primaryLanguage));
        return new PersonRow(cells.text("source"), cells.text("externalId"), cells.text("username"),
                cells.text("userId"), cells.text("primaryLanguage"), cells.names(primaryLanguage, PERSON_NAMES,
                        CsvImportService::withPersonName), cells.attributes(), cells.text("id"), cells.nulls());
    }

    private EmploymentRow employment(Cells cells) {
        return new EmploymentRow(cells.text("source"), cells.text("externalId"), cells.text("username"),
                cells.text("userId"), cells.text("staffNumber"), cells.text("employmentType"), cells.text("grade"),
                cells.text("status"), cells.text("leaveReason"), cells.decimal("workPercent"),
                cells.text("contractEndDate"), cells.attributes(), cells.text("validFrom"), cells.text("validTo"),
                cells.text("endReason"), cells.text("employerExternalId"), cells.text("personExternalId"),
                cells.text("personId"), cells.text("employerId"), cells.text("id"),
                cells.text("continuesEmploymentExternalId"), cells.text("continuesEmploymentId"),
                cells.text("externalEmployer"), cells.nulls());
    }

    private AssignmentRow assignment(Cells cells) {
        return new AssignmentRow(cells.text("source"), cells.text("externalId"), cells.text("employmentExternalId"),
                cells.text("orgUnitExternalId"), cells.text("jobTitle"), cells.text("occupation"),
                cells.text("assignmentKind"), cells.bool("head"), cells.bool("primary"),
                cells.decimal("allocationPercent"), cells.integer("displayOrder"), cells.attributes(),
                cells.text("validFrom"), cells.text("validTo"), cells.text("employmentId"), cells.text("orgUnitId"),
                cells.text("id"), cells.text("workLocation"), cells.text("costCenter"), cells.nulls());
    }

    // A row matches a stored record by the ID of any system, so the row of a second system finds it too.
    private Optional<String> resourceOf(ExternalId.Kind kind, String source, String externalId,
            String defaultSource) {
        return importService.records().externalIds().resourceOf(kind, source == null ? defaultSource : source,
                externalId);
    }

    private static <T> Optional<T> quietly(Supplier<T> read) {
        try {
            return Optional.of(read.get());
        } catch (FuhitoException e) {
            // The import reports why the stored row cannot be read on the date.
            return Optional.empty();
        }
    }

    // The names of one language with one field set. Null names stand for names with no field set.
    @FunctionalInterface
    private interface NameSetter<T> {
        T with(T names, String field, String text);
    }

    private static OrgUnitName withUnitName(OrgUnitName names, String field, String text) {
        OrgUnitName base = names == null ? new OrgUnitName(null, null, null) : names;
        return switch (field) {
            case "name" -> new OrgUnitName(text, base.shortName(), base.phoneticName());
            case "shortName" -> new OrgUnitName(base.name(), text, base.phoneticName());
            default -> new OrgUnitName(base.name(), base.shortName(), text);
        };
    }

    private static PersonName withPersonName(PersonName names, String field, String text) {
        PersonName base = names == null ? new PersonName(null, null) : names;
        return field.equals("name") ? new PersonName(text, base.phoneticName()) : new PersonName(base.name(), text);
    }

    /**
     * The cells of one row by column. An empty cell is a key that the row leaves out, and {@code #CLEAR} is a key
     * with null, as in a JSON row. A cell of only spaces fails, as such a text in a JSON row does.
     */
    private record Cells(Map<String, String> values, Map<String, String> headers, List<AttributeDefinition> defined,
            Set<String> cleared) {

        // The key of the row for a column with another name.
        private static final Map<String, String> KEYS = Map.of("assignmentKind", "kind");

        static Cells of(Map<String, String> columns, List<String> cells, List<AttributeDefinition> defined) {
            // A comma that should have been in quotes moves every later value one column to the right.
            if (cells.subList(Math.min(columns.size(), cells.size()), cells.size()).stream()
                    .anyMatch(cell -> !cell.isEmpty())) {
                throw FuhitoException.invalid("csv.too_many_cells",
                        Map.of("columns", Integer.toString(columns.size()), "cells", Integer.toString(cells.size())));
            }
            Map<String, String> values = new LinkedHashMap<>();
            Set<String> cleared = new HashSet<>();
            int i = 0;
            for (String column : columns.keySet()) {
                // A row reads stored data by its cells, before the import checks the row.
                String value = Checks.storable(column, i < cells.size() ? cells.get(i) : "");
                i++;
                if (!value.isEmpty()) {
                    Checks.optionalText(column, value);
                }
                values.put(column, value);
                if (value.equals(CLEAR) && column.indexOf(':') < 0 && !column.startsWith(ATTRIBUTE_COLUMN)) {
                    cleared.add(KEYS.getOrDefault(column, column));
                }
            }
            return new Cells(values, columns, defined, cleared);
        }

        Set<String> nulls() {
            return Set.copyOf(cleared);
        }

        // A column of a defined attribute sets it, and #CLEAR removes it. A row with no such column keeps every
        // stored attribute.
        Map<String, Object> attributes() {
            if (defined.stream().noneMatch(definition -> values.containsKey(ATTRIBUTE_COLUMN + definition.name()))) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (AttributeDefinition definition : defined) {
                String column = ATTRIBUTE_COLUMN + definition.name();
                if (isClear(column)) {
                    result.put(definition.name(), null);
                } else if (definition.multiple()) {
                    List<String> items = list(column);
                    if (items != null) {
                        result.put(definition.name(), items.stream().map(item -> typed(definition, column, item))
                                .toList());
                    }
                } else {
                    String text = text(column);
                    if (text != null) {
                        result.put(definition.name(), typed(definition, column, text));
                    }
                }
            }
            return result;
        }

        // A stored attribute reads back from JSON as Integer, Long, BigInteger, or Double. A value of the same
        // type compares equal to it, so a row that matches what is stored changes nothing. A number must be one
        // that a double gives back the same, as in a JSON row. So a cell such as 1e999999999 fails before its
        // digits are made.
        private static Object asJson(String column, String text) {
            BigDecimal value = number(column, text);
            if (!JsonNumbers.fitsDouble(value)) {
                throw badNumber(column, text);
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

        // A cell of a value of a type that a row names, such as the value to fill of a definition. A type that is
        // not one of the types keeps the text, and the import rejects the type.
        Object value(String column, String type, boolean multiple) {
            AttributeDefinition.Type of = Arrays.stream(AttributeDefinition.Type.values())
                    .filter(each -> each.name().equals(type))
                    .findFirst()
                    .orElse(AttributeDefinition.Type.STRING);
            if (multiple) {
                List<String> items = list(column);
                return items == null ? null : items.stream().map(item -> typed(of, column, item)).toList();
            }
            String text = text(column);
            return text == null ? null : typed(of, column, text);
        }

        private static Object typed(AttributeDefinition definition, String column, String text) {
            return typed(definition.type(), column, text);
        }

        // The definition checks a text later, as it does for a JSON import.
        private static Object typed(AttributeDefinition.Type type, String column, String text) {
            return switch (type) {
                case NUMBER -> asJson(column, text);
                case BOOLEAN -> truth(column, text);
                default -> text;
            };
        }

        List<String> list(String field) {
            return ListCell.read(field, values.getOrDefault(field, ""));
        }

        boolean blank(String field) {
            return values.getOrDefault(field, "").isEmpty();
        }

        private boolean isClear(String field) {
            return values.getOrDefault(field, "").equals(CLEAR);
        }

        String text(String field) {
            String value = values.getOrDefault(field, "");
            return value.isEmpty() || value.equals(CLEAR) ? null : value;
        }

        // A number has the form of a number of JSON, as in a JSON row. Java would also read ５ and +5.
        Integer integer(String field) {
            return parsed(field, value -> (int) NumberText.whole(value, Integer.MIN_VALUE, Integer.MAX_VALUE)
                    .orElseThrow(() -> badNumber(field, value)));
        }

        BigDecimal decimal(String field) {
            return parsed(field, value -> number(field, value));
        }

        private static BigDecimal number(String field, String value) {
            return NumberText.decimal(value).orElseThrow(() -> badNumber(field, value));
        }

        private static FuhitoException badNumber(String field, String value) {
            return FuhitoException.invalid("csv.bad_number", Map.of("field", field, "value", value));
        }

        Boolean bool(String field) {
            return parsed(field, value -> truth(field, value));
        }

        private static Boolean truth(String field, String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (TRUE.contains(lower)) {
                return true;
            }
            if (FALSE.contains(lower)) {
                return false;
            }
            throw FuhitoException.invalid("csv.bad_boolean", Map.of("field", field, "value", value));
        }

        private <T> T parsed(String field, Function<String, T> parse) {
            String value = text(field);
            return value == null ? null : parse.apply(value);
        }

        // The primary language of the cell, or else the stored one, which places the cells of the primary names.
        String primaryLanguage(Supplier<Optional<String>> stored) {
            String language = text("primaryLanguage");
            if (language == null && UNIT_NAMES.stream().anyMatch(field -> !blank(field))) {
                language = stored.get().orElse(null);
            }
            return language == null ? null : Checks.languageTag("primaryLanguage", language);
        }

        // The columns with no language set the primary names. A column like "name:en" sets the names of one
        // language, and "name:en" with #CLEAR removes them, so the other names of that language stay blank. The
        // import keeps the other names that are stored.
        <T> Map<String, T> names(String primaryLanguage, List<String> fields, NameSetter<T> set) {
            List<String> given = fields.stream().filter(field -> !blank(field)).toList();
            Map<String, T> result = new LinkedHashMap<>();
            Set<String> removed = new HashSet<>();
            Map<String, String> others = new LinkedHashMap<>();
            values.forEach((column, value) -> {
                int colon = column.indexOf(':');
                if (colon < 0 || value.isEmpty() || column.startsWith(ATTRIBUTE_COLUMN)) {
                    return;
                }
                String field = column.substring(0, colon);
                String language = Checks.languageTag(column, column.substring(colon + 1));
                if (language.equals(primaryLanguage) && given.contains(field)) {
                    throw FuhitoException.invalid("invalid.duplicate_language",
                            Map.of("field", headers.get(column), "value", language));
                }
                if (field.equals("name") && value.equals(CLEAR)) {
                    removed.add(language);
                    result.put(language, null);
                } else {
                    others.put(column, language);
                    result.put(language, name(result.get(language), language, field, column, set));
                }
            });
            given.forEach(field -> others.put(field, primaryLanguage));
            others.forEach((column, language) -> {
                if (removed.contains(language)) {
                    throw FuhitoException.invalid("csv.clear_conflict",
                            Map.of("field", headers.get(column), "value", language));
                }
            });
            if (given.isEmpty()) {
                return result.isEmpty() ? null : result;
            }
            if (primaryLanguage == null) {
                throw FuhitoException.invalid("invalid.required", Map.of("field", "primaryLanguage"));
            }
            T primary = result.get(primaryLanguage);
            for (String field : given) {
                primary = name(primary, primaryLanguage, field, field, set);
            }
            result.put(primaryLanguage, primary);
            return result;
        }

        private <T> T name(T names, String language, String field, String column, NameSetter<T> set) {
            if (isClear(column)) {
                cleared.add("names." + language + "." + field);
            }
            return set.with(names, field, text(column));
        }
    }
}
