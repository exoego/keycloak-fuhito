package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportReport.Counts;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvImportServiceTest {

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
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, assignmentStore, refItems,
            Clock.systemUTC());
    private final EmploymentService employments =
            new EmploymentService(employmentStore, assignmentStore, refItems, users,
            new MemoryReportingLineStore());
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, employmentStore, orgUnitStore, refItems,
            new MemoryReportingLineStore());
    private final ImportService.People people =
            new ImportService.People(employments, employmentStore, assignments, assignmentStore, users);
    private final CsvImportService service = new CsvImportService(
            new ImportService(new RefItemService(refItems), refItems, orgUnits, orgUnitStore, people), refItems,
            orgUnitStore, orgUnits, people);

    private CsvImportService.Result load(String list, String effectiveDate, String csv) {
        return service.apply(list, csv, effectiveDate, false, null);
    }

    private static List<String> errors(CsvImportService.Result result) {
        return result.errors().stream().map(e -> "line " + e.line() + " row " + e.row() + " " + e.error().code())
                .toList();
    }

    private OrgUnitState unit(String externalId, String asOf) {
        return orgUnits.get(orgUnitStore.findUnitByExternalId("import", externalId).orElseThrow().id(), asOf);
    }

    private void item(RefKind kind, String code) {
        refItems.insert(new RefItem(kind + "-" + code, kind, code, code, Map.of(), 0, Map.of(), true));
    }

    @Test
    void loadsReferenceItemsWithJapaneseHeaders() {
        CsvImportService.Result result = load("refItems", "2026-04-01", """
                ﻿種類,コード,名称,名称:en,表示順,有効
                OCCUPATION,nurse,看護師,Nurse,2,はい
                OCCUPATION,old,旧職種,,,いいえ
                """);

        assertEquals(List.of(), errors(result));
        assertEquals(new Counts(2, 0, 0), result.report().counts().get("refItems"));
        RefItem nurse = refItems.findByCode(RefKind.OCCUPATION, "nurse").orElseThrow();
        assertEquals("看護師", nurse.name());
        assertEquals(Map.of("en", "Nurse"), nurse.names());
        assertEquals(2, nurse.displayOrder());
        assertFalse(refItems.findByCode(RefKind.OCCUPATION, "old").orElseThrow().active());
    }

    @Test
    void aBlankCellKeepsTheStoredValueAndClearRemovesIt() {
        item(RefKind.UNIT_TYPE, "ward");
        load("orgUnits", "2026-04-01", """
                外部ID,名称,名称:en,略称,よみがな,組織種別,親の外部ID,表示順
                H,X病院,X Hospital,X,えっくす,,,1
                W,1病棟,Ward 1,1病,いちびょうとう,ward,H,5
                """);

        CsvImportService.Result result = load("orgUnits", "2027-04-01", """
                externalId,name,name:en,name:ja,shortName,phoneticName,unitType,parentExternalId,displayOrder
                W,東病棟,,ひがし,#CLEAR,,#CLEAR,,
                H,,,,,,,,
                """);

        assertEquals(List.of(), errors(result));
        assertEquals(new Counts(0, 1, 1), result.report().counts().get("orgUnits"));
        OrgUnitState ward = unit("W", "2027-04-01");
        assertEquals("東病棟", ward.version().name());
        assertEquals(Map.of("en", "Ward 1", "ja", "ひがし"), ward.version().names());
        assertNull(ward.version().shortName());
        assertEquals("いちびょうとう", ward.version().phoneticName());
        assertNull(ward.unitType());
        assertEquals(unit("H", "2027-04-01").unit().id(), ward.parentId());
        assertEquals(5, ward.version().displayOrder());
        assertEquals("1病棟", unit("W", "2027-03-31").version().name());

        // Clearing the parent makes the unit a root. Clearing one language removes only that language.
        load("orgUnits", "2028-04-01", "外部ID,親の外部ID,名称:en\nW,#CLEAR,#CLEAR\n");
        assertNull(unit("W", "2028-04-01").parentId());
        assertEquals(Map.of("ja", "ひがし"), unit("W", "2028-04-01").version().names());
    }

    @Test
    void loadsStaffAndATransferFromCsv() {
        item(RefKind.OCCUPATION, "nurse");
        item(RefKind.EMPLOYMENT_TYPE, "part-time");
        load("orgUnits", "2026-04-01", "外部ID,名称\nH,X病院\nW,1病棟\n");
        load("employments", "2026-04-01", """
                外部ID,ユーザー名,職員番号,雇用形態,勤務割合,開始日
                E1,nurse,N-001,part-time,50,2020-04-01
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
        var employment = employments.get(employmentStore.findByExternalId("import", "E1").orElseThrow().id());
        assertEquals("user-nurse", employment.employment().userId());
        assertEquals("N-001", employment.employment().staffNumber());
        var terms = employment.terms().get(1);
        assertEquals("ON_LEAVE", terms.terms().status().name());
        assertEquals("part-time", terms.employmentType());
        assertEquals(0, new BigDecimal("50").compareTo(terms.terms().workPercent()));
        var old = assignments.get(assignmentStore.findAssignmentIdByExternalId("import", "A1").orElseThrow());
        assertEquals("2027-04-01", old.period().to().toString());
        assertTrue(old.rows().getFirst().row().head());
        assertEquals("nurse", old.rows().getFirst().occupation());
        assertEquals(3, old.rows().getFirst().row().displayOrder());
        assertEquals(new Counts(1, 1, 0), moved.report().counts().get("assignments"));
    }

    @Test
    void reportsBadRowsWithTheirLines() {
        load("orgUnits", "2026-04-01", "外部ID,名称\nH,X病院\n");

        CsvImportService.Result result = load("orgUnits", "2026-04-01", """
                外部ID,名称,表示順,親の外部ID

                A,A課,x,H
                "B","two
                lines",1,NOPE
                C,,,H
                D,D課,2,H
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
        assertEquals("invalid.choice", failure("people", "a\n1\n").code());
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
        CsvImportService.Result result = service.apply("orgUnits", " EXTERNALID , Name ,SOURCE\nH,X,\nK,Y,other\n",
                "2026-04-01", false, "sheet");

        assertEquals(List.of(), errors(result));
        assertEquals("X", orgUnits.get(orgUnitStore.findUnitByExternalId("sheet", "H").orElseThrow().id(),
                "2026-04-01").version().name());
        assertTrue(orgUnitStore.findUnitByExternalId("other", "K").isPresent());
    }
}
