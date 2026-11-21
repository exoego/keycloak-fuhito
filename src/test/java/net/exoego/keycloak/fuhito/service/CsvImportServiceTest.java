package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportReport.Counts;
import net.exoego.keycloak.fuhito.store.LongWrite;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.UnitNames.ja;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvImportServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private final MemoryExternalIdStore externalIdStore = externalIds;
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final UserDirectory users = new UserDirectory() {
        @Override
        public boolean exists(String userId) {
            return userId.startsWith("user-");
        }

        @Override
        public Optional<String> findIdByUsername(String username) {
            return username.equals("nobody") ? Optional.empty() : Optional.of("user-" + username);
        }
    };
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, externalIdStore, assignmentStore, refItems,
            Clock.systemUTC());
    private final MemoryPersonStore personStore = new MemoryPersonStore();
    private final EmploymentService employments =
            new EmploymentService(employmentStore, externalIdStore, assignmentStore, refItems, personStore,
            new MemoryReportingLineStore(), new MemoryUnitRoleStore(), new AnyEmployer());
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, externalIdStore, employmentStore, orgUnitStore, refItems,
            new MemoryReportingLineStore());
    private final ImportService.People people =
            new ImportService.People(employments, employmentStore, assignments, assignmentStore, users,
                    new PersonService(personStore, externalIdStore, users, id -> false));
    private final MemoryAttributeDefinitionStore definitions = new MemoryAttributeDefinitionStore();
    private final MemoryAttributeValueStore values = new MemoryAttributeValueStore();
    private final CsvImportService service = new CsvImportService(
            new ImportService(new RefItemService(refItems), refItems, orgUnits, orgUnitStore, people,
                    new ImportService.Setup(new RealmKindService(new MemoryRealmKindStore(), refItems, definitions),
                            new AttributeDefinitionService(definitions, values, refItems),
                            new HierarchyService(new MemoryHierarchyStore())),
                    new ImportService.Organizations(new OrganizationLinkService(orgUnitStore, id -> false),
                            id -> false, id -> { }), new ImportService.Records(null, null, null,
                            new ExternalIdService(externalIdStore), null), LongWrite.NONE), refItems, orgUnits,
            people,
            definitions::list);

    // The record that an ID of a system names, as an import finds it.
    private String stored(ExternalId.Kind kind, String source, String externalId) {
        return externalIdStore.idOf(kind, source, externalId).orElseThrow();
    }

    private CsvImportService.Result load(String list, String effectiveDate, String csv) {
        return service.apply(list, csv, effectiveDate, false, null);
    }

    private static List<String> errors(CsvImportService.Result result) {
        return result.errors().stream().map(e -> "line " + e.line() + " row " + e.row() + " " + e.error().code())
                .toList();
    }

    private OrgUnitState unit(String externalId, String asOf) {
        return orgUnits.get(stored(ExternalId.Kind.ORG_UNIT, "import", externalId), asOf);
    }

    private void item(RefKind kind, String code) {
        refItems.insert(new RefItem(kind + "-" + code, kind, code, ItemNames.of(code), 0, Map.of(), true));
    }

    @Test
    void loadsReferenceItemsWithJapaneseHeaders() {
        CsvImportService.Result result = load("refItems", "2026-04-01", """
                ﻿外部ID,種類,コード,主言語,名称,名称:en,表示順,有効
                N,OCCUPATION,nurse,ja,看護師,Nurse,2,はい
                O,OCCUPATION,old,ja,旧職種,,,いいえ
                """);

        assertEquals(List.of(), errors(result));
        assertEquals(new Counts(2, 0, 0), result.report().counts().get("refItems"));
        RefItem nurse = refItems.findByCode(RefKind.OCCUPATION, "nurse").orElseThrow();
        assertEquals(new Localized<>("ja", Map.of("ja", new RefItemName("看護師"), "en", new RefItemName("Nurse"))),
                nurse.names());
        assertEquals(2, nurse.displayOrder());
        assertFalse(refItems.findByCode(RefKind.OCCUPATION, "old").orElseThrow().active());
    }

    @Test
    void loadsTheMarkOfRollupUnitsAndABlankCellKeepsIt() {
        CsvImportService.Result result = load("refItems", "2026-04-01", """
                ﻿外部ID,種類,コード,主言語,名称,集計
                R,UNIT_TYPE,region,ja,地域,はい
                C,UNIT_TYPE,committee,ja,委員会,
                """);

        assertEquals(List.of(), errors(result));
        assertTrue(refItems.findByCode(RefKind.UNIT_TYPE, "region").orElseThrow().rollup());
        assertFalse(refItems.findByCode(RefKind.UNIT_TYPE, "committee").orElseThrow().rollup());
        load("refItems", "2026-04-01", "externalId,name,rollup\nR,地方,\n");
        assertTrue(refItems.findByCode(RefKind.UNIT_TYPE, "region").orElseThrow().rollup());
    }

    @Test
    void loadsTheNamesOfAReferenceItemFromTheDateOfTheRow() {
        load("refItems", "2026-04-01",
                "externalId,kind,code,primaryLanguage,name,name:en\nC,JOB_TITLE,chief,ja,主任,Chief\n");

        // A blank name keeps the names of the date of the row.
        CsvImportService.Result result = load("refItems", "2026-04-01", """
                外部ID,名称,名称:en,開始日
                C,,Senior Chief,2027-04-01
                C,,,2027-13-01
                """);

        assertEquals(List.of("line 3 row 1 invalid.date"), errors(result));
        RefItem item = refItems.findByCode(RefKind.JOB_TITLE, "chief").orElseThrow();
        assertEquals(List.of("null 主任 Chief", "2027-04-01 主任 Senior Chief"), refItems.names(item.id()).stream()
                .map(n -> n.validFrom() + " " + n.names().primary().name() + " "
                        + n.names().byLanguage().get("en").name())
                .toList());
    }

    @Test
    void aNewReferenceItemNeedsAPrimaryLanguageAndClearRemovesALanguage() {
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("refItems", "2026-04-01", "externalId,kind,code,name\nC,JOB_TITLE,chief,主任\n")));
        load("refItems", "2026-04-01",
                "externalId,kind,code,primaryLanguage,name,name:en\nC,JOB_TITLE,chief,ja,主任,Chief\n");

        CsvImportService.Result result = load("refItems", "2026-04-01", "externalId,name,name:en\nC,主任,#CLEAR\n");

        assertEquals(List.of(), errors(result));
        assertEquals(Localized.of("ja", new RefItemName("主任")),
                refItems.findByCode(RefKind.JOB_TITLE, "chief").orElseThrow().names());
    }

    @Test
    void aBlankCellKeepsTheStoredValueAndClearRemovesIt() {
        item(RefKind.UNIT_TYPE, "ward");
        load("orgUnits", "2026-04-01", """
                外部ID,主言語,名称,略称,よみがな,名称:en,略称:EN,組織種別,親の外部ID,表示順
                H,ja,X病院,X,えっくす,X Hospital,,,,1
                W,JA,1病棟,1病,いちびょうとう,Ward 1,W1,ward,H,5
                """);

        CsvImportService.Result result = load("orgUnits", "2027-04-01", """
                externalId,name,name:en,name:zh-hant,shortName,phoneticName:en,unitType,parentExternalId,displayOrder
                W,東病棟,,東病房,#CLEAR,ward one,#CLEAR,,
                H,,,,,,,,
                """);

        assertEquals(List.of(), errors(result));
        assertEquals(new Counts(0, 1, 1), result.report().counts().get("orgUnits"));
        OrgUnitState ward = unit("W", "2027-04-01");
        assertEquals(new Localized<>("ja", Map.of("ja", new OrgUnitName("東病棟", null, "いちびょうとう"),
                "en", new OrgUnitName("Ward 1", "W1", "ward one"), "zh-Hant", new OrgUnitName("東病房", null, null))),
                ward.version().names());
        assertNull(ward.unitType());
        assertEquals(unit("H", "2027-04-01").unit().id(), ward.parentId());
        assertEquals(5, ward.version().displayOrder());
        assertEquals("1病棟", unit("W", "2027-03-31").version().names().primary().name());

        // Clearing the parent makes the unit a root. Clearing the name of a language removes that language.
        load("orgUnits", "2028-04-01", "外部ID,親の外部ID,名称:en,よみがな:zh-Hant\nW,#CLEAR,#CLEAR,#CLEAR\n");
        assertNull(unit("W", "2028-04-01").parentId());
        assertEquals(new Localized<>("ja", Map.of("ja", new OrgUnitName("東病棟", null, "いちびょうとう"),
                "zh-Hant", new OrgUnitName("東病房", null, null))), unit("W", "2028-04-01").version().names());

        // The columns with no language set the names of the primary language.
        load("orgUnits", "2029-04-01", "外部ID,主言語,名称\nW,zh-Hant,東翼病房\n");
        assertEquals(new Localized<>("zh-Hant", Map.of("ja", new OrgUnitName("東病棟", null, "いちびょうとう"),
                "zh-Hant", new OrgUnitName("東翼病房", null, null))), unit("W", "2029-04-01").version().names());

        assertEquals(List.of("line 2 row 0 invalid.language_tag", "line 3 row 1 invalid.language_tag"),
                errors(load("orgUnits", "2030-04-01", "外部ID,主言語,名称:x_y\nW,ja,West\nH,x_y,\n")));
    }

    @Test
    void aRowGivesANameOfThePrimaryLanguageInOneOfItsTwoColumns() {
        load("orgUnits", "2026-04-01", """
                externalId,primaryLanguage,name,shortName,phoneticName
                U,ja,営業部,営業,えいぎょう
                V,ja,経理部,経理,けいり
                """);
        Localized<OrgUnitName> stored = unit("U", "2026-04-01").version().names();

        // A stored unit keeps its primary language when the cell is blank. A column writes the tag in any case.
        CsvImportService.Result twice = load("orgUnits", "2027-04-01", """
                外部ID,主言語,名称,略称,よみがな,名称:JA,略称:ja,よみがな:ja
                U,,販売部,,,営業部,,
                V,,,経理,,,経理課,
                N1,ja,人事部,#CLEAR,,,人事,
                N2,JA,総務部,,#CLEAR,,,#CLEAR
                """);
        CsvImportService.Result inTheRow = load("orgUnits", "2027-04-01",
                "externalId,primaryLanguage,name,name:en\nU,en,Sales,Sales Division\n");

        assertEquals(List.of("line 2 row 0 invalid.duplicate_language", "line 3 row 1 invalid.duplicate_language",
                "line 4 row 2 invalid.duplicate_language", "line 5 row 3 invalid.duplicate_language"), errors(twice));
        assertEquals(List.of(Map.of("field", "名称:JA", "value", "ja"), Map.of("field", "略称:ja", "value", "ja"),
                Map.of("field", "略称:ja", "value", "ja"), Map.of("field", "よみがな:ja", "value", "ja")),
                twice.errors().stream().map(e -> e.error().params()).toList());
        assertEquals(List.of("line 2 row 0 invalid.duplicate_language"), errors(inTheRow));
        assertEquals(Map.of("field", "name:en", "value", "en"), inTheRow.errors().getFirst().error().params());
        assertEquals(stored, unit("U", "2027-04-01").version().names());
        assertTrue(externalIdStore.idOf(ExternalId.Kind.ORG_UNIT, "import", "N1").isEmpty());

        // A blank cell in one of the two columns leaves the name to the other. #CLEAR there removes it.
        assertEquals(List.of(), errors(load("orgUnits", "2027-04-01", """
                外部ID,名称,略称,よみがな,名称:ja,略称:ja,よみがな:ja
                U,,#CLEAR,,販売部,,#CLEAR
                V,経理部,,,,経理課,
                """)));
        assertEquals(Localized.of("ja", new OrgUnitName("販売部", null, null)),
                unit("U", "2027-04-01").version().names());
        assertEquals(Localized.of("ja", new OrgUnitName("経理部", "経理課", "けいり")),
                unit("V", "2027-04-01").version().names());
        assertEquals(stored, unit("U", "2026-04-01").version().names());
    }

    @Test
    void aRowThatRemovesALanguageWithClearLeavesTheOtherNamesOfThatLanguageBlank() {
        load("orgUnits", "2026-04-01", """
                externalId,primaryLanguage,name,shortName,name:en,shortName:en,phoneticName:en,name:zh-Hant
                U,ja,営業部,営業,Sales,Sales Dept,sales,營業部
                """);
        load("people", "2026-04-01", """
                externalId,primaryLanguage,name,phoneticName,name:en,phoneticName:en
                P,ja,山田,やまだ,Yamada,yamada
                """);
        Localized<OrgUnitName> unitNames = unit("U", "2027-04-01").version().names();
        String person = stored(ExternalId.Kind.PERSON, "import", "P");
        Localized<PersonName> personNames = personStore.find(person).orElseThrow().names();

        // The order of the columns does not matter, and #CLEAR counts as a cell.
        assertEquals("csv.clear_conflict shortName:en en",
                rowError("orgUnits", "externalId,name:en,shortName:en\nU,#CLEAR,Sales Division\n"));
        assertEquals("csv.clear_conflict shortName:en en",
                rowError("orgUnits", "externalId,shortName:en,name:en\nU,Sales Division,#CLEAR\n"));
        assertEquals("csv.clear_conflict よみがな:EN en", rowError("orgUnits", "外部ID,よみがな:EN,名称:en\nU,#CLEAR,#CLEAR\n"));
        // The columns with no language hold names of the primary language, also the stored one.
        assertEquals("csv.clear_conflict 略称 ja", rowError("orgUnits", "外部ID,名称:ja,略称\nU,#CLEAR,営業課\n"));
        assertEquals("csv.clear_conflict よみがな ja",
                rowError("orgUnits", "外部ID,よみがな,主言語,名称:JA\nU,えいぎょうぶ,ja,#CLEAR\n"));
        assertEquals("csv.clear_conflict phoneticName:en en",
                rowError("people", "externalId,name:en,phoneticName:en\nP,#CLEAR,Yamada\n"));
        assertEquals("csv.clear_conflict phoneticName:en en",
                rowError("people", "externalId,phoneticName:en,name:en\nP,Yamada,#CLEAR\n"));
        assertEquals("csv.clear_conflict よみがな ja", rowError("people", "外部ID,よみがな,名称:ja\nP,やまだ,#CLEAR\n"));
        assertEquals(unitNames, unit("U", "2027-04-01").version().names());
        assertEquals(personNames, personStore.find(person).orElseThrow().names());

        // #CLEAR of a language alone removes it, and a name alone sets it. Other languages may do either.
        assertEquals(List.of(), errors(load("orgUnits", "2027-04-01", """
                externalId,shortName,name:en,shortName:en,phoneticName:en,name:zh-Hant,shortName:zh-hant
                U,営業課,#CLEAR,,,,營業
                """)));
        assertEquals(List.of(), errors(load("people", "2027-04-01", """
                externalId,phoneticName,name:en,phoneticName:en
                P,やまだ たろう,#CLEAR,
                """)));
        assertEquals(new Localized<>("ja", Map.of("ja", new OrgUnitName("営業部", "営業課", null),
                "zh-Hant", new OrgUnitName("營業部", "營業", null))), unit("U", "2027-04-01").version().names());
        assertEquals(Localized.of("ja", new PersonName("山田", "やまだ たろう")),
                personStore.find(person).orElseThrow().names());
    }

    // The error of the only row of a file on 2027-04-01, with its field and value.
    private String rowError(String list, String csv) {
        CsvImportService.Result result = load(list, "2027-04-01", csv);
        assertEquals(1, result.errors().size(), list + " " + csv + " " + result.errors());
        FuhitoException error = result.errors().getFirst().error();
        return error.code() + " " + error.params().get("field") + " " + error.params().get("value");
    }

    @Test
    void loadsStaffAndATransferFromCsv() {
        item(RefKind.OCCUPATION, "nurse");
        item(RefKind.EMPLOYMENT_TYPE, "part-time");
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称\nH,ja,X病院\nW,ja,1病棟\n");
        load("employments", "2026-04-01", """
                外部ID,ユーザー名,職員番号,雇用形態,勤務割合,契約期間満了日,開始日
                E1,nurse,N-001,part-time,50,2028-03-31,2020-04-01
                """);
        CsvImportService.Result first = load("assignments", "2026-04-01", """
                外部ID,雇用の外部ID,組織の外部ID,職種,長,主務,配分割合,表示順
                A1,E1,W,nurse,○,はい,100,3
                """);
        assertEquals(List.of(), errors(first));

        // A leave keeps everything else. The assignment ends, and a new one starts in the hospital.
        CsvImportService.Result leave = load("employments", "2027-04-01", "外部ID,状態\nE1,ON_LEAVE\n");
        CsvImportService.Result moved = load("assignments", "2027-04-01", """
                外部ID,雇用の外部ID,組織の外部ID,主務,終了日
                A2,E1,H,true,
                A1,,,,2027-04-01
                """);

        assertEquals(List.of(), errors(leave));
        assertEquals(List.of(), errors(moved));
        var employment = employments.get(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1"));
        assertEquals("user-nurse", employment.employment().userId());
        assertEquals("N-001", employment.employment().staffNumber());
        var terms = employment.terms().get(1);
        assertEquals("ON_LEAVE", terms.terms().status().name());
        assertEquals("part-time", terms.employmentType());
        assertEquals(0, new BigDecimal("50").compareTo(terms.terms().workPercent()));
        assertEquals("2028-03-31", terms.terms().contractEndDate().toString());
        var old = assignments.get(stored(ExternalId.Kind.ASSIGNMENT, "import", "A1"));
        assertEquals("2027-04-01", old.period().to().toString());
        assertTrue(old.rows().getFirst().row().head());
        assertEquals("nurse", old.rows().getFirst().occupation());
        assertEquals(3, old.rows().getFirst().row().displayOrder());
        assertEquals(new Counts(1, 1, 0), moved.report().counts().get("assignments"));
    }

    @Test
    void aFileOfATransferEndsTheOldEmploymentInAnyOrder() {
        load("employments", "2020-04-01", "外部ID,ユーザー名\nE1,nurse\nD1,doctor\n");

        // The nurse moves, and the file ends the old employment after the row of the new one. The doctor moves
        // with no end of the old employment.
        CsvImportService.Result transfer = load("employments", "2026-04-01", """
                外部ID,ユーザー名,開始日,終了日,引き継ぎ元の雇用の外部ID
                E2,nurse,2026-04-01,,E1
                E1,nurse,,2026-04-01,
                D2,doctor,2026-04-01,,D1
                """);

        assertEquals(List.of("line 4 row 2 employment.continues_holds"), errors(transfer));
        assertEquals(stored(ExternalId.Kind.EMPLOYMENT, "import", "D1"),
                transfer.errors().getFirst().error().params().get("id"));
    }

    @Test
    void aBlankParentKeepsAParentThatHasNoExternalIdOfTheSource() {
        String hospital = orgUnits.create(new NewOrgUnit(null, "ja", ja("Hospital"), null, null,
                "2026-04-01", null, null, null, null)).unit().id();
        // A parent with an ID of another system, which a unit of the source has too.
        String clinic = orgUnits.create(new NewOrgUnit(null, "ja", ja("Clinic"), null, null,
                "2026-04-01", null, null, "X", "hr")).unit().id();
        orgUnits.create(new NewOrgUnit(null, "ja", ja("Not the parent"), null, null, "2026-04-01",
                null, null, "X", "sheet"));
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称,ソース\nW1,ja,1病棟,sheet\nW2,ja,2病棟,sheet\n");
        String first = stored(ExternalId.Kind.ORG_UNIT, "sheet", "W1");
        String second = stored(ExternalId.Kind.ORG_UNIT, "sheet", "W2");
        orgUnits.move(first, hospital, "2026-04-01");
        orgUnits.move(second, clinic, "2026-04-01");

        CsvImportService.Result renamed = load("orgUnits", "2026-10-01",
                "外部ID,名称,ソース\nW1,第1病棟,sheet\nW2,第2病棟,sheet\n");

        assertEquals(List.of(), errors(renamed));
        assertEquals(hospital, orgUnits.get(first, "2026-10-01").parentId());
        assertEquals("第1病棟", orgUnits.get(first, "2026-10-01").version().names().primary().name());
        assertEquals(clinic, orgUnits.get(second, "2026-10-01").parentId());
        // The cell #CLEAR still makes it a root.
        load("orgUnits", "2027-04-01", "外部ID,親の外部ID,ソース\nW1,#CLEAR,sheet\n");
        assertNull(orgUnits.get(first, "2027-04-01").parentId());
    }

    @Test
    void aRowWithMoreCellsThanTheHeaderIsReported() {
        CsvImportService.Result result = load("orgUnits", "2026-04-01", """
                外部ID,主言語,名称,よみがな
                W1,ja,1病棟, 東館,いちびょうとう
                W2,ja,2病棟,にびょうとう,
                """);

        assertEquals(List.of("line 2 row 0 csv.too_many_cells"), errors(result));
        assertEquals(Map.of("columns", "4", "cells", "5"), result.errors().getFirst().error().params());
        // An empty cell at the end is fine.
        assertEquals("にびょうとう", unit("W2", "2026-04-01").version().names().primary().phoneticName());
    }

    @Test
    void aCellWithNulIsReportedByItsColumn() {
        String nul = Character.toString(0);
        CsvImportService.Result result = load("orgUnits", "2026-04-01",
                "外部ID,主言語,名称\nW1,ja,a" + nul + "b\nW" + nul + ",,1病棟\nW3,ja,3病棟\n");

        assertEquals(List.of("line 2 row 0 invalid.character", "line 3 row 1 invalid.character"), errors(result));
        assertEquals(Map.of("field", "name", "codePoint", "U+0000"), result.errors().getFirst().error().params());
        assertEquals("externalId", result.errors().getLast().error().params().get("field"));
        assertEquals(new Counts(1, 0, 0), result.report().counts().get("orgUnits"));
    }

    @Test
    void aReasonStaysWithTheLeaveAndGoesWithTheReturn() {
        item(RefKind.LEAVE_REASON, "parental");
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称\nH,ja,X病院\n");
        load("employments", "2026-04-01", "外部ID,ユーザー名,開始日\nE1,nurse,2020-04-01\n");

        assertEquals(List.of(), errors(load("employments", "2027-04-01", "外部ID,状態,休職理由\nE1,ON_LEAVE,parental\n")));
        // A row that leaves out the reason keeps it while the person stays on leave.
        assertEquals(new Counts(0, 0, 1), load("employments", "2027-06-01", "外部ID,状態\nE1,ON_LEAVE\n").report()
                .counts().get("employments"));
        assertEquals(List.of(), errors(load("employments", "2028-04-01", "外部ID,状態\nE1,ACTIVE\n")));

        var terms = employments.get(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1")).terms();
        assertEquals(Arrays.asList(null, "parental", null), terms.stream().map(t -> t.leaveReason()).toList());
    }

    @Test
    void aColumnSetsADefinedAttributeAndKeepsTheOthers() {
        new AttributeDefinitionService(definitions, values, refItems).create("ORG_UNIT", "beds", "NUMBER", null, null, null, null);
        new AttributeDefinitionService(definitions, values, refItems).create("ORG_UNIT", "open", "BOOLEAN", null,
                null, null, null);
        orgUnits.create(new NewOrgUnit(null, "ja", ja("1病棟"), null, null, "2026-04-01", null,
                Map.of("note", "kept"), "W", "import"));

        assertEquals(List.of(), errors(load("orgUnits", "2026-05-01", """
                外部ID,attributes.beds,attributes.open
                W,30,はい
                """)));
        // The value has the type that it gets when it is read back from JSON.
        assertEquals(Map.of("note", "kept", "beds", 30, "open", true),
                unit("W", "2026-05-01").version().attributes());
        assertEquals(List.of(), errors(load("orgUnits", "2026-05-15", """
                外部ID,attributes.open
                W,#CLEAR
                """)));
        assertEquals(Map.of("note", "kept", "beds", 30), unit("W", "2026-05-15").version()
                .attributes());
        load("orgUnits", "2026-05-20", """
                外部ID,attributes.beds
                W,30.50
                """);
        assertEquals(30.5, unit("W", "2026-05-20").version().attributes().get("beds"));
        load("orgUnits", "2026-05-25", """
                外部ID,attributes.beds
                W,99999999999
                """);
        assertEquals(99_999_999_999L, unit("W", "2026-05-25").version().attributes().get("beds"));
        load("orgUnits", "2026-05-26", """
                外部ID,attributes.beds
                W,1e20
                """);
        assertEquals(new BigInteger("100000000000000000000"),
                unit("W", "2026-05-26").version().attributes().get("beds"));
        // A double gives back another number for each of these, as a client in JavaScript reads it.
        for (String cell : List.of("many", "99999999999999999999", "50.000000000000001", "1e400", "1e-400",
                "1e999999999")) {
            assertEquals(List.of("line 2 row 0 csv.bad_number"), errors(load("orgUnits", "2026-06-01", """
                    外部ID,attributes.beds
                    W,%s
                    """.formatted(cell))), cell);
        }
        assertEquals("csv.bad_column", assertThrows(FuhitoException.class, () -> load("orgUnits", "2026-06-01", """
                外部ID,attributes.rooms
                W,3
                """)).code());
    }

    @Test
    void aCellOfAValueToFillIsReadByTheTypeOfTheDefinition() {
        values.add(AttributeDefinition.Target.ORG_UNIT, "u-1", Map.of());

        assertEquals(List.of(), errors(load("attributeDefinitions", "2026-04-01", """
                externalId,target,name,type,multiple,required,fillValue
                S,ORG_UNIT,sizes,NUMBER,true,true,30|2.50
                O,ORG_UNIT,open,BOOLEAN,,,
                """)));
        // A row of a stored definition that leaves out the type has the stored one.
        assertEquals(List.of(), errors(load("attributeDefinitions", "2026-04-01", """
                外部ID,必須,値のない行に入れる値
                O,はい,はい
                """)));
        assertEquals(Map.of("sizes", List.of(30, 2.5), "open", true), values.rows.getFirst().attributes());
        assertEquals(List.of("line 2 row 0 csv.bad_number"), errors(load("attributeDefinitions", "2026-04-01", """
                externalId,target,name,type,required,fillValue
                B,ORG_UNIT,beds,NUMBER,true,５
                """)));
    }

    @Test
    void aColumnOfAListAttributeTakesItemsBetweenVerticalBars() {
        AttributeDefinitionService service = new AttributeDefinitionService(definitions, values, refItems);
        service.create("ORG_UNIT", "tags", "STRING", null, true, null, null);
        service.create("ORG_UNIT", "sizes", "NUMBER", null, true, null, null);
        service.create("ORG_UNIT", "flags", "BOOLEAN", null, true, null, null);
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称\nW,ja,1病棟\n");

        assertEquals(List.of(), errors(load("orgUnits", "2026-05-01", """
                外部ID,attributes.tags,attributes.sizes,attributes.flags
                W, night\\|day | C:\\\\ward ,30|2.50,はい|false
                """)));
        // Each item has the type of the definition, as a single value has.
        assertEquals(Map.of("tags", List.of("night|day", "C:\\ward"), "sizes", List.of(30, 2.5),
                "flags", List.of(true, false)), unit("W", "2026-05-01").version().attributes());
        assertEquals(List.of(), errors(load("orgUnits", "2026-05-15", "外部ID,attributes.tags\nW,icu\n")));
        assertEquals(List.of("icu"), unit("W", "2026-05-15").version().attributes().get("tags"));

        CsvImportService.Result bad = load("orgUnits", "2026-06-01", """
                外部ID,attributes.tags,attributes.sizes,attributes.flags
                W,night||icu,,
                W,C:\\ward,,
                W,,30|many,
                W,,,はい|maybe
                """);
        assertEquals(List.of("line 2 row 0 csv.empty_item", "line 3 row 1 csv.bad_escape",
                "line 4 row 2 csv.bad_number", "line 5 row 3 csv.bad_boolean"), errors(bad));
        assertEquals(Map.of("field", "attributes.tags", "value", "night||icu"),
                bad.errors().getFirst().error().params());
        assertEquals(Map.of("field", "attributes.sizes", "value", "many"), bad.errors().get(2).error().params());

        load("orgUnits", "2026-07-01", """
                外部ID,attributes.tags,attributes.sizes,attributes.flags
                W,#CLEAR,#CLEAR,
                """);
        assertEquals(Map.of("flags", List.of(true, false)), unit("W", "2026-07-01").version().attributes());
    }

    @Test
    void aCellOfSuccessorsNamesExternalIdsWithAVerticalBarOrABackslash() {
        assertEquals(List.of(), errors(load("orgUnits", "2026-04-01", """
                外部ID,主言語,名称
                OLD,ja,旧病棟
                100|2024,ja,1病棟
                C:\\200,ja,2病棟
                """)));

        assertEquals(List.of("line 2 row 0 csv.empty_item"), errors(load("orgUnits", "2026-05-01", """
                外部ID,終了日,後継の外部ID
                OLD,2027-04-01,100\\|2024||C:\\\\200
                """)));
        assertEquals(List.of(), errors(load("orgUnits", "2026-05-01", """
                外部ID,終了日,後継の外部ID
                OLD,2027-04-01, 100\\|2024 | C:\\\\200
                """)));

        assertEquals(List.of(stored(ExternalId.Kind.ORG_UNIT, "import", "100|2024"),
                stored(ExternalId.Kind.ORG_UNIT, "import", "C:\\200")).stream().sorted().toList(),
                orgUnits.successions(stored(ExternalId.Kind.ORG_UNIT, "import", "OLD")).successors().stream()
                        .map(OrgUnitSuccession::successorId).sorted().toList());
    }

    @Test
    void loadsPeopleWithNoUserFromCsv() {
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称\nH,ja,X病院\n");

        assertEquals(List.of(), errors(load("people", "2026-04-01", """
                外部ID,主言語,名称,名称:en,よみがな
                P1,ja,山田 花子,Hanako Yamada,やまだ はなこ
                """)));
        assertEquals(List.of(), errors(load("employments", "2026-04-01", """
                外部ID,人の外部ID,開始日,終了日
                E1,P1,1990-04-01,2000-04-01
                """)));
        // A blank cell keeps the stored name.
        assertEquals(List.of(), errors(load("people", "2026-04-01", "外部ID,よみがな\nP1,やまだ\n")));

        var person = personStore.find(stored(ExternalId.Kind.PERSON, "import", "P1")).orElseThrow();
        assertEquals(new Localized<>("ja", Map.of("ja", new PersonName("山田 花子", "やまだ"),
                "en", new PersonName("Hanako Yamada", null))), person.names());
        assertEquals(person.id(), employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1")).orElseThrow().personId());
        assertEquals(new Counts(0, 0, 1), load("employments", "2026-04-01", "外部ID,終了日\nE1,2000-04-01\n")
                .report().counts().get("employments"));

        // A column of a language sets the names of that language. #CLEAR in its name removes the language.
        assertEquals(List.of(), errors(load("people", "2026-04-01",
                "外部ID,名称:en,名称:zh-Hant,よみがな:zh-Hant\nP1,#CLEAR,山田花子,shān tián\n")));
        assertEquals(new Localized<>("ja", Map.of("ja", new PersonName("山田 花子", "やまだ"),
                "zh-Hant", new PersonName("山田花子", "shān tián"))),
                personStore.find(person.id()).orElseThrow().names());
        // The names of a new person need the primary language.
        assertEquals(List.of("line 2 row 0 invalid.required"), errors(load("people", "2026-04-01",
                "外部ID,名称\nP2,山田 太郎\n")));
    }

    @Test
    void aFileOfAnotherSystemFindsAPersonByTheIdThatSystemHasForIt() {
        assertEquals(List.of(), errors(load("people", "2026-04-01", "外部ID,主言語,名称,よみがな\nP1,ja,山田 花子,やまだ\n")));
        String person = stored(ExternalId.Kind.PERSON, "import", "P1");
        externalIdStore.insert(new ExternalId("row-pay", ExternalId.Kind.PERSON, person, "payroll", "PAY-1", 1));

        CsvImportService.Result result = service.apply("people", "externalId,name\nPAY-1,山田 華子\n", "2026-04-01",
                false, "payroll");

        assertEquals(new Counts(0, 1, 0), result.report().counts().get("people"));
        var renamed = personStore.find(person).orElseThrow();
        // A blank cell keeps what is stored, also for a row of another system.
        assertEquals(Localized.of("ja", new PersonName("山田 華子", "やまだ")), renamed.names());
        assertEquals("import", renamed.source());
    }

    @Test
    void aNewPersonOfAFileCannotTakeAUserWhoHasAPerson() {
        assertEquals(List.of(), errors(load("people", "2026-04-01", "externalId,username\nP1,nurse\n")));

        CsvImportService.Result taken = load("people", "2026-04-01", "externalId,username\nP2,nurse\n");

        assertEquals(List.of("line 2 row 0 import.user_taken"), errors(taken));
        assertEquals(Map.of("userId", "user-nurse", "personId", stored(ExternalId.Kind.PERSON, "import", "P1")),
                taken.errors().getFirst().error().params());
        assertEquals(new Counts(0, 0, 1), load("people", "2026-04-01", "externalId,username\nP1,nurse\n")
                .report().counts().get("people"));
    }

    @Test
    void reportsBadRowsWithTheirLines() {
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称\nH,ja,X病院\n");

        CsvImportService.Result result = load("orgUnits", "2026-04-01", """
                外部ID,主言語,名称,表示順,親の外部ID

                A,ja,A課,x,H
                "B",ja,"two
                lines",1,NOPE
                C,ja,,,H
                D,ja,D課,2,H
                """);

        assertEquals(List.of("line 3 row 0 csv.bad_number", "line 4 row 1 import.parent_unknown",
                "line 6 row 2 invalid.required"), errors(result));
        assertEquals(Map.of("field", "displayOrder", "value", "x"), result.errors().getFirst().error().params());
        assertEquals(new Counts(1, 0, 0), result.report().counts().get("orgUnits"));

        CsvImportService.Result flags = load("assignments", "2026-04-01",
                "外部ID,雇用の外部ID,組織の外部ID,主務,配分割合\nA1,E1,H,たぶん,\nA2,E1,H,,half\n");
        assertEquals(List.of("line 2 row 0 csv.bad_boolean", "line 3 row 1 csv.bad_number"), errors(flags));
    }

    // A number has the form of a number of JSON, as in a JSON row. Java would read the full-width 5, +5, .5, and 05.
    @Test
    void aNumberCellHasTheFormOfJson() {
        new AttributeDefinitionService(definitions, values, refItems).create("ORG_UNIT", "beds", "NUMBER", null, null, null, null);
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称\nH,ja,X病院\n");

        for (String cell : List.of("\uFF15", "\u0665", "+5", "05", "-", "1.0", "1e2", "0x10", "1,000", "5.")) {
            CsvImportService.Result order = load("orgUnits", "2026-05-01", "外部ID,表示順\nH,\"" + cell + "\"\n");
            assertEquals(List.of("line 2 row 0 csv.bad_number"), errors(order), cell);
            assertEquals(Map.of("field", "displayOrder", "value", cell), order.errors().getFirst().error().params());
        }
        for (String cell : List.of("\uFF15\uFF10", "+50", ".5", "50.", "050", "5e", "0x10", "Infinity")) {
            CsvImportService.Result beds = load("orgUnits", "2026-05-01", "外部ID,attributes.beds\nH,\"" + cell
                    + "\"\n");
            assertEquals(List.of("line 2 row 0 csv.bad_number"), errors(beds), cell);
            assertEquals(Map.of("field", "attributes.beds", "value", cell), beds.errors().getFirst().error().params());
            CsvImportService.Result work = load("employments", "2026-05-01", "外部ID,勤務割合\nE1,\"" + cell + "\"\n");
            assertEquals(Map.of("field", "workPercent", "value", cell), work.errors().getFirst().error().params());
            CsvImportService.Result share = load("assignments", "2026-05-01",
                    "外部ID,雇用の外部ID,組織の外部ID,配分割合\nA1,E1,H,\"" + cell + "\"\n");
            assertEquals(Map.of("field", "allocationPercent", "value", cell),
                    share.errors().getFirst().error().params());
        }

        assertEquals(List.of(), errors(load("orgUnits", "2026-05-01", "外部ID,表示順,attributes.beds\nH,-0,5E1\n")));
        assertEquals(0, unit("H", "2026-05-01").version().displayOrder());
        assertEquals(50, unit("H", "2026-05-01").version().attributes().get("beds"));
        assertEquals(List.of(), errors(load("orgUnits", "2026-05-02", "外部ID,表示順\nH,-2147483648\n")));
        assertEquals(Integer.MIN_VALUE, unit("H", "2026-05-02").version().displayOrder());
        assertEquals(List.of("line 2 row 0 csv.bad_number"),
                errors(load("orgUnits", "2026-05-03", "外部ID,表示順\nH,2147483648\n")));
    }

    @Test
    void rejectsABadFile() {
        assertEquals("csv.no_header", failure("orgUnits", "\n\n").code());
        FuhitoException unknown = failure("orgUnits", "外部ID,名前\nH,x\n");
        assertEquals("csv.bad_column", unknown.code());
        assertEquals(Map.of("column", "名前"), unknown.params());
        assertEquals("csv.bad_column", failure("orgUnits", "外部ID,名称,name\nH,x,y\n").code());
        assertEquals("csv.bad_column", failure("orgUnits", "外部ID,名称,ユーザー名\nH,x,y\n").code());
        assertEquals("csv.bad_column", failure("orgUnits", "外部ID,名称,表示順:en\nH,x,1\n").code());
        // Two columns of one name in one language, as the tags are written in any case.
        FuhitoException twice = failure("orgUnits", "外部ID,主言語,名称:ja,name:JA\nH,ja,x,y\n");
        assertEquals("invalid.duplicate_language", twice.code());
        assertEquals(Map.of("field", "name:JA", "value", "ja"), twice.params());
        assertEquals(Map.of("field", "phoneticName:en-US", "value", "en-US"), failure("people",
                "externalId,phoneticName:en-us,name:en-us,phoneticName:en-US\nP,x,y,z\n").params());
        assertEquals("csv.open_quote", failure("orgUnits", "外部ID,名称\nH,\"x\n").code());
        assertEquals("invalid.choice", failure("rooms", "a\n1\n").code());
        assertEquals("invalid.required", failure(null, "a\n1\n").code());
        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.apply("orgUnits", null, "2026-04-01", false, null))
                        .code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> service.apply("orgUnits", "外部ID\nH\n", "x", false, null)).code());
        assertEquals("invalid.code", assertThrows(FuhitoException.class,
                () -> service.apply("orgUnits", "外部ID\nH\n", "2026-04-01", false, "bad source")).code());
    }

    private FuhitoException failure(String list, String csv) {
        return assertThrows(FuhitoException.class, () -> load(list, "2026-04-01", csv));
    }

    @Test
    void decodesAFileInItsCharset() {
        String text = "外部ID,名称\nH,山田\n";

        assertEquals(text, CsvImportService.decode(text.getBytes(StandardCharsets.UTF_8), null));
        assertEquals("﻿" + text, CsvImportService.decode(("﻿" + text).getBytes(StandardCharsets.UTF_8), null));
        assertEquals(text, CsvImportService.decode(text.getBytes(Charset.forName("Shift_JIS")), "Shift_JIS"));
        assertEquals(text, CsvImportService.decode(text.getBytes(StandardCharsets.UTF_16), "UTF-16"));
        assertEquals(text, CsvImportService.decode(text.getBytes(StandardCharsets.UTF_16LE), "utf-16le"));
        // After the first line, a 00 byte is a NUL character in a cell, which the checks of values reject.
        assertEquals("a\nb\u0000c", CsvImportService.decode(bytes("a\nb", 0x00, 'c'), "UTF-8"));
    }

    @Test
    void rejectsAFileThatIsNotValidInItsCharset() {
        assertEquals(Map.of("charset", "UTF-8", "bytes", "C3", "offset", "6", "line", "3"),
                badEncoding(bytes("a\r\nb\rc", 0xC3, 0x28), null));
        assertEquals(Map.of("charset", "UTF-8", "bytes", "FF", "offset", "2", "line", "2"),
                badEncoding(bytes("a\n", 0xFF), "UTF-8"));
        assertEquals(Map.of("charset", "UTF-8", "bytes", "ED A0 80", "offset", "1", "line", "1"),
                badEncoding(bytes("a", 0xED, 0xA0, 0x80), null));
        assertEquals(Map.of("charset", "UTF-8", "bytes", "E3 81", "offset", "2", "line", "1"),
                badEncoding(bytes("a,", 0xE3, 0x81), null));
        assertEquals(Map.of("charset", "UTF-8", "bytes", "8E", "offset", "0", "line", "1"),
                badEncoding("山田".getBytes(Charset.forName("Shift_JIS")), null));
        assertEquals(Map.of("charset", "Shift_JIS", "bytes", "A0", "offset", "2", "line", "2"),
                badEncoding(bytes("a\n", 0xA0), "sjis"));
        assertEquals(Map.of("charset", "UTF-16", "bytes", "78", "offset", "4", "line", "1"),
                badEncoding(bytes("", 0xFE, 0xFF, 0x00, 'a', 'x'), "UTF-16"));
    }

    @Test
    void rejectsAUtf16FileUnlessTheCharsetIsUtf16() {
        String text = "externalId,name\nH,x\n";

        assertEquals(Map.of("charset", "UTF-8", "bytes", "FE FF", "offset", "0", "line", "1"),
                badEncoding(text.getBytes(StandardCharsets.UTF_16), null));
        assertEquals(Map.of("charset", "Shift_JIS", "bytes", "FF FE", "offset", "0", "line", "1"),
                badEncoding(("﻿" + text).getBytes(StandardCharsets.UTF_16LE), "Shift_JIS"));
        assertEquals(Map.of("charset", "UTF-8", "bytes", "00", "offset", "1", "line", "1"),
                badEncoding(text.getBytes(StandardCharsets.UTF_16LE), null));
        assertEquals(Map.of("charset", "UTF-8", "bytes", "00", "offset", "0", "line", "1"),
                badEncoding(text.getBytes(StandardCharsets.UTF_16BE), null));
        assertEquals(text, CsvImportService.decode(text.getBytes(StandardCharsets.UTF_16LE), "UTF-16LE"));
        assertEquals(text, CsvImportService.decode(text.getBytes(Charset.forName("UTF-32")), "UTF-32"));
    }

    @Test
    void rejectsAnUnknownCharset() {
        for (String name : List.of("no-such-charset", "+abc", "")) {
            FuhitoException e = assertThrows(FuhitoException.class,
                    () -> CsvImportService.decode(new byte[] {'a'}, name));
            assertEquals("csv.bad_charset", e.code());
            assertEquals(Map.of("charset", name), e.params());
        }
    }

    private static Map<String, String> badEncoding(byte[] file, String charset) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> CsvImportService.decode(file, charset));
        assertEquals("csv.bad_encoding", e.code());
        return e.params();
    }

    private static byte[] bytes(String text, int... more) {
        byte[] start = text.getBytes(StandardCharsets.UTF_8);
        byte[] file = Arrays.copyOf(start, start.length + more.length);
        for (int i = 0; i < more.length; i++) {
            file[start.length + i] = (byte) more[i];
        }
        return file;
    }

    @Test
    void headersIgnoreCaseAndSpacesAndTheSourceCanBeSet() {
        CsvImportService.Result result = service.apply("orgUnits", " EXTERNALID , PrimaryLanguage, Name ,SOURCE\nH,ja,X,\nK,ja,Y,other\n",
                "2026-04-01", false, "sheet");

        assertEquals(List.of(), errors(result));
        assertEquals("X", orgUnits.get(stored(ExternalId.Kind.ORG_UNIT, "sheet", "H"),
                "2026-04-01").version().names().primary().name());
        assertTrue(externalIdStore.idOf(ExternalId.Kind.ORG_UNIT, "other", "K").isPresent());
    }

    @Test
    void aRowForAUnitThatDoesNotExistOnTheDateIsReported() {
        load("orgUnits", "2026-04-01", """
                externalId,primaryLanguage,name,shortName
                H,en,Hospital,Hosp
                """);

        // The unit starts in 2026. A row on a date in 2025 matches it only when it gives no other value.
        CsvImportService.Result blank = load("orgUnits", "2025-04-01", """
                externalId,name,shortName
                H,,
                """);
        CsvImportService.Result result = load("orgUnits", "2025-04-01", """
                externalId,primaryLanguage,name,shortName
                H,en,,#CLEAR
                """);

        assertEquals(List.of(), errors(blank));
        assertEquals(List.of("line 2 row 0 org_unit.not_valid_on"), errors(result));
    }

    @Test
    void aRowWithNoKeyIsReportedForEveryKindOfList() {
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("refItems", "2026-04-01", "kind,code,primaryLanguage,name\nUNIT_TYPE,ward,en,Ward\n")));
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("refKinds", "2026-04-01", "code,primaryLanguage,name\nsite,ja,拠点\n")));
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("hierarchies", "2026-04-01", "code,name\nproject,Project\n")));
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("attributeDefinitions", "2026-04-01", "target,name,type\nPERSON,note,STRING\n")));
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("orgUnits", "2026-04-01", "externalId,name\n,Ward\n")));
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("employments", "2026-04-01", "externalId,username\n,nurse\n")));
        assertEquals(List.of("line 2 row 0 invalid.required"),
                errors(load("assignments", "2026-04-01", "externalId,employmentExternalId\n,E1\n")));
    }

    @Test
    void aRowCanNameItsSourceAndAUserById() {
        load("orgUnits", "2026-04-01", "source,externalId,primaryLanguage,name\nhr-2,W,en,Ward\n");
        CsvImportService.Result people = load("employments", "2026-04-01", """
                source,externalId,userId
                hr-2,E1,user-nurse
                """);
        CsvImportService.Result placed = load("assignments", "2026-04-01", """
                source,externalId,employmentExternalId,orgUnitExternalId
                hr-2,A1,E1,W
                """);

        assertEquals(List.of(), errors(people));
        assertEquals(List.of(), errors(placed));
        // The same rows again find what is stored under that source.
        assertEquals(new Counts(0, 0, 1), load("employments", "2026-04-01", "source,externalId\nhr-2,E1\n")
                .report().counts().get("employments"));
        assertEquals(new Counts(0, 0, 1), load("assignments", "2026-04-01", "source,externalId\nhr-2,A1\n")
                .report().counts().get("assignments"));
        assertTrue(externalIdStore.idOf(ExternalId.Kind.EMPLOYMENT, "import", "E1").isEmpty());
    }

    @Test
    void aShortRowHasEmptyCellsAndClearWorksOnNumbersAndFlags() {
        item(RefKind.UNIT_TYPE, "ward");
        load("refItems", "2026-04-01", "id,name,displayOrder,active\nUNIT_TYPE-ward,Ward,5,no\n");
        var stored = refItems.findByCode(RefKind.UNIT_TYPE, "ward").orElseThrow();
        assertEquals(5, stored.displayOrder());
        assertFalse(stored.active());

        // The second row stops after its name. The cells that are missing keep what is stored.
        CsvImportService.Result result = load("refItems", "2026-04-01", """
                id,kind,code,name,displayOrder,active
                UNIT_TYPE-ward,UNIT_TYPE,ward,Ward,#CLEAR,#CLEAR
                """);
        CsvImportService.Result kept = load("refItems", "2026-04-01", """
                id,kind,code,name,displayOrder,active
                UNIT_TYPE-ward,UNIT_TYPE,ward,East ward
                """);

        assertEquals(List.of(), errors(result));
        assertEquals(List.of(), errors(kept));
        var changed = refItems.findByCode(RefKind.UNIT_TYPE, "ward").orElseThrow();
        assertEquals(ItemNames.of("East ward"), changed.names());
        assertEquals(0, changed.displayOrder());
        assertTrue(changed.active());
    }

    @Test
    void aBlankCellLeavesTheKeyOutAndClearGivesNull() {
        item(RefKind.ASSIGNMENT_KIND, "concurrent");
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称,タイムゾーン,日の始まり\nW,ja,1病棟,Asia/Tokyo,05:00\n");
        load("employments", "2026-04-01", "外部ID,ユーザー名\nE1,nurse\n");
        load("assignments", "2026-04-01", "外部ID,雇用の外部ID,組織の外部ID,所属区分\nA1,E1,W,concurrent\n");
        String assignment = stored(ExternalId.Kind.ASSIGNMENT, "import", "A1");

        CsvImportService.Result kept = load("assignments", "2026-05-01", "externalId,assignmentKind\nA1,\n");
        CsvImportService.Result cleared = load("assignments", "2026-05-01", "externalId,assignmentKind\nA1,#CLEAR\n");
        CsvImportService.Result times = load("orgUnits", "2026-05-01", """
                externalId,timeZone,dayStart,successorIds
                W,#CLEAR,,#CLEAR
                """);

        assertEquals(new Counts(0, 0, 1), kept.report().counts().get("assignments"));
        assertEquals(new Counts(0, 1, 0), cleared.report().counts().get("assignments"));
        assertNull(assignments.get(assignment).rows().getLast().kind());
        assertEquals(List.of(), errors(times));
        OrgUnit unit = orgUnitStore.findUnit(stored(ExternalId.Kind.ORG_UNIT, "import", "W")).orElseThrow();
        assertNull(unit.timeZone());
        assertEquals("05:00", unit.dayStart());
    }
}
