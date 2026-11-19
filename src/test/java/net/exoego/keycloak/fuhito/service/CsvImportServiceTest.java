package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
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
    private final CsvImportService service = new CsvImportService(
            new ImportService(new RefItemService(refItems), refItems, orgUnits, orgUnitStore, people,
                    new ImportService.Setup(new RealmKindService(new MemoryRealmKindStore(), refItems),
                            new AttributeDefinitionService(definitions, refItems),
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
        new AttributeDefinitionService(definitions, refItems).create("ORG_UNIT", "beds", "NUMBER", null, null, null);
        new AttributeDefinitionService(definitions, refItems).create("ORG_UNIT", "open", "BOOLEAN", null,
                null, null);
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
                W,99999999999999999999
                """);
        assertEquals(new BigInteger("99999999999999999999"),
                unit("W", "2026-05-26").version().attributes().get("beds"));
        assertEquals(List.of("line 2 row 0 csv.bad_number"), errors(load("orgUnits", "2026-06-01", """
                外部ID,attributes.beds
                W,many
                """)));
        assertEquals("csv.bad_column", assertThrows(FuhitoException.class, () -> load("orgUnits", "2026-06-01", """
                外部ID,attributes.rooms
                W,3
                """)).code());
    }

    @Test
    void aColumnOfAListAttributeTakesAJsonArray() {
        new AttributeDefinitionService(definitions, refItems).create("ORG_UNIT", "tags", "STRING", null, true, null);
        load("orgUnits", "2026-04-01", "外部ID,主言語,名称\nW,ja,1病棟\n");

        assertEquals(List.of(), errors(load("orgUnits", "2026-05-01", """
                外部ID,attributes.tags
                W,"[""night"",""icu""]"
                """)));
        assertEquals(Map.of("tags", List.of("night", "icu")), unit("W", "2026-05-01").version().attributes());
        assertEquals(List.of("line 2 row 0 csv.bad_list", "line 3 row 1 csv.bad_list"),
                errors(load("orgUnits", "2026-06-01", """
                        外部ID,attributes.tags
                        W,night
                        W,"{""a"": 1}"
                        """)));
        load("orgUnits", "2026-07-01", """
                外部ID,attributes.tags
                W,#CLEAR
                """);
        assertEquals(Map.of(), unit("W", "2026-07-01").version().attributes());
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

    @Test
    void rejectsABadFile() {
        assertEquals("csv.no_header", failure("orgUnits", "\n\n").code());
        FuhitoException unknown = failure("orgUnits", "外部ID,名前\nH,x\n");
        assertEquals("csv.bad_column", unknown.code());
        assertEquals(Map.of("column", "名前"), unknown.params());
        assertEquals("csv.bad_column", failure("orgUnits", "外部ID,名称,name\nH,x,y\n").code());
        assertEquals("csv.bad_column", failure("orgUnits", "外部ID,名称,ユーザー名\nH,x,y\n").code());
        assertEquals("csv.bad_column", failure("orgUnits", "外部ID,名称,表示順:en\nH,x,1\n").code());
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

        // The unit starts in 2026. On a date in 2025 it has no stored values to keep.
        CsvImportService.Result result = load("orgUnits", "2025-04-01", """
                externalId,name,shortName
                H,,
                """);

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
}
