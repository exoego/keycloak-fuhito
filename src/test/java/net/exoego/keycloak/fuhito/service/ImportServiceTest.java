package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.UnitRole;
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
import net.exoego.keycloak.fuhito.service.ImportDocument.RefKindRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.ReportingLineRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.UnitRoleRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Counts;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.PeopleNames.japanese;
import static net.exoego.keycloak.fuhito.service.UnitNames.ja;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportServiceTest {

    private final MemoryExternalIdStore externalIdStore = new MemoryExternalIdStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, externalIdStore, assignmentStore, refItems,
            Clock.systemUTC());
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final Map<String, String> usernames = Map.of("nurse", "user-nurse", "doctor", "user-doctor");
    private final UserDirectory users = new UserDirectory() {
        @Override
        public boolean exists(String userId) {
            return usernames.containsValue(userId);
        }

        @Override
        public Optional<String> findIdByUsername(String username) {
            return Optional.ofNullable(usernames.get(username));
        }
    };
    private final MemoryPersonStore personStore = new MemoryPersonStore();
    private final MemoryReportingLineStore lineStore = new MemoryReportingLineStore();
    private final MemoryUnitRoleStore roleStore = new MemoryUnitRoleStore();
    private final MemoryQualificationStore qualificationStore = new MemoryQualificationStore();
    private final MemoryChangeOrderStore orderStore = new MemoryChangeOrderStore();
    private final QualificationService qualifications = new QualificationService(qualificationStore, externalIdStore, refItems,
            personStore, users, Clock.systemUTC());
    private final UnitRoleService roles = new UnitRoleService(roleStore,
            new UnitRoleService.Sources(employmentStore, orgUnitStore, refItems), Today.of(Clock.systemUTC()));
    private final ReportingLineService lines = new ReportingLineService(lineStore, new ReportingLineService.Sources(
            assignmentStore, employmentStore, orgUnitStore, refItems, users, personStore), Clock.systemUTC());
    private final EmploymentService employments =
            new EmploymentService(employmentStore, externalIdStore, assignmentStore, refItems, personStore,
            new MemoryReportingLineStore(), new MemoryUnitRoleStore(), new AnyEmployer());
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, externalIdStore, employmentStore, orgUnitStore, refItems,
            new MemoryReportingLineStore());
    private final RealmKindService kinds = new RealmKindService(new MemoryRealmKindStore(), refItems);
    private final HierarchyService hierarchies = new HierarchyService(new MemoryHierarchyStore());
    private final List<String> managed = new ArrayList<>();
    private final OrganizationDirectory organizations = new OrganizationDirectory() {
        @Override
        public boolean exists(String organizationId) {
            return organizationId.startsWith("org-");
        }

        @Override
        public Optional<String> idOfAlias(String alias) {
            return alias.startsWith("x-") ? Optional.of("org-" + alias) : Optional.empty();
        }
    };
    private final AttributeDefinitionService definitions =
            new AttributeDefinitionService(new MemoryAttributeDefinitionStore(), refItems, kinds::isKnown);
    private final ImportService service = new ImportService(new RefItemService(refItems), refItems, orgUnits,
            orgUnitStore, new ImportService.People(employments, employmentStore, assignments, assignmentStore, users,
                    new PersonService(personStore, externalIdStore, users, id -> false)),
            new ImportService.Setup(kinds, definitions, hierarchies),
            new ImportService.Organizations(new OrganizationLinkService(orgUnitStore, organizations), organizations,
                    managed::add),
            new ImportService.Records(lines, roles, qualifications, new ExternalIdService(externalIdStore),
                    new ChangeOrderService(orderStore, null, users, Clock.systemUTC())));

    @BeforeEach
    void shareTheRecords() {
        externalIdStore.records(ExternalId.Kind.ORG_UNIT,
                id -> orgUnitStore.findUnit(id).map(unit -> new ExternalId.Origin(unit.source())));
        externalIdStore.records(ExternalId.Kind.PERSON,
                id -> personStore.find(id).map(person -> new ExternalId.Origin(person.source())));
        externalIdStore.records(ExternalId.Kind.EMPLOYMENT,
                id -> employmentStore.find(id).map(employment -> new ExternalId.Origin(employment.source())));
    }

    // The record that an ID of a system names, as an import finds it.
    private String stored(ExternalId.Kind kind, String source, String externalId) {
        return externalIdStore.idOf(kind, source, externalId).orElseThrow();
    }

    private static ImportDocument doc(String effectiveDate, boolean dryRun, String source, List<RefItemRow> refItems,
            List<OrgUnitRow> orgUnits) {
        return new ImportDocument(effectiveDate, dryRun, source, refItems, orgUnits, null, null);
    }

    private static OrgUnitRow unit(String externalId, String name, String parentExternalId) {
        return new OrgUnitRow(null, externalId, null, "ja", ja(name), null, parentExternalId, null, null,
                null, null, null, null);
    }

    private ImportReport importUnits(String effectiveDate, OrgUnitRow... rows) {
        return service.apply(doc(effectiveDate, false, null, null, List.of(rows)));
    }

    private List<String> tree(String asOf) {
        return Trees.all(orgUnits, asOf, null).stream()
                .map(s -> s.version().names().primary().name() + (s.parentId() == null ? "" : " < "
                        + orgUnits.get(s.parentId(), asOf).version().names().primary().name()))
                .toList();
    }

    private static List<String> errors(ImportReport report) {
        return report.errors().stream().map(e -> e.list() + "[" + e.row() + "] " + e.error().code()).toList();
    }

    @Test
    void importsReferenceItemsAndIsIdempotent() {
        List<RefItemRow> rows = List.of(
                new RefItemRow("UNIT_TYPE", "hospital", "ja",
                        Map.of("ja", new RefItemName(" 病院 "), "en", new RefItemName("Hospital")), 1, Map.of("k", "v"),
                        null),
                new RefItemRow("OCCUPATION", "nurse", "ja", ItemNames.japanese("看護師"), null, null, false));

        ImportReport first = service.apply(doc("2026-04-01", false, null, rows, null));
        ImportReport second = service.apply(doc("2026-04-01", false, null, rows, null));

        assertEquals(Map.of("refItems", new Counts(2, 0, 0)), first.counts());
        assertEquals(Map.of("refItems", new Counts(0, 0, 2)), second.counts());
        assertEquals(List.of(), first.errors());
        assertEquals(2, first.writes().size());
        assertTrue(first.writes().getFirst().created());
        assertEquals("FUHITO_REF_ITEM", first.writes().getFirst().resourceType());
        assertEquals("fuhito/ref-items/" + first.writes().getFirst().resourceId(), first.writes().getFirst().path());
        assertEquals(List.of(), second.writes());
        RefItem hospital = refItems.findByCode(RefKind.UNIT_TYPE, "hospital").orElseThrow();
        assertEquals(new Localized<>("ja", Map.of("ja", new RefItemName("病院"), "en", new RefItemName("Hospital"))),
                hospital.names());
        assertEquals(new ImportReport.Write.OfRefItem(true, hospital), first.writes().getFirst());
        assertFalse(refItems.findByCode(RefKind.OCCUPATION, "nurse").orElseThrow().active());
    }

    @Test
    void importsTheKindsOfTheRealmAndRenamesAStoredOne() {
        List<RefKindRow> rows = List.of(new RefKindRow("site", "ja",
                Map.of("ja", new RefKindName("拠点"), "en", new RefKindName("Site"))));

        ImportReport first = service.apply(kinds(rows));
        ImportReport second = service.apply(kinds(rows));
        ImportReport renamed = service.apply(kinds(List.of(
                new RefKindRow("site", "ja", Map.of("ja", new RefKindName("勤務地"))),
                new RefKindRow("UNIT_TYPE", "ja", Map.of("ja", new RefKindName("組織種別"))))));

        assertEquals(Map.of("refKinds", new Counts(1, 0, 0)), first.counts());
        assertEquals("fuhito/ref-kinds/site", first.writes().getFirst().path());
        assertEquals(Map.of("refKinds", new Counts(0, 0, 1)), second.counts());
        assertEquals(Map.of("refKinds", new Counts(0, 1, 0)), renamed.counts());
        // A fixed kind is in every realm, so a row cannot add it.
        assertEquals(List.of("refKinds[1] ref_kind.code_taken"), errors(renamed));
        assertEquals(Localized.of("ja", new RefKindName("勤務地")), kinds.get("site").names());
    }

    private static ImportDocument kinds(List<RefKindRow> rows) {
        return ImportDocument.of("2026-04-01", false, null, "refKinds", rows);
    }

    @Test
    void importsTheDefinitionsOfAttributesAndChangesTheRuleOfAStoredOne() {
        List<AttributeDefinitionRow> rows = List.of(
                new AttributeDefinitionRow("PERSON", "note", "STRING", null, null, null),
                new AttributeDefinitionRow("ORG_UNIT", "type", "REF_ITEM", "UNIT_TYPE", null, true));

        ImportReport first = service.apply(definitions(rows));
        ImportReport second = service.apply(definitions(rows));
        ImportReport changed = service.apply(definitions(List.of(
                new AttributeDefinitionRow("PERSON", "note", "STRING", null, null, true),
                new AttributeDefinitionRow("PERSON", "bad", "STRING", "UNIT_TYPE", null, null))));

        assertEquals(Map.of("attributeDefinitions", new Counts(2, 0, 0)), first.counts());
        assertEquals("fuhito/attribute-definitions/" + first.writes().getFirst().resourceId(),
                first.writes().getFirst().path());
        assertEquals(Map.of("attributeDefinitions", new Counts(0, 0, 2)), second.counts());
        assertEquals(Map.of("attributeDefinitions", new Counts(0, 1, 0)), changed.counts());
        assertEquals(List.of("attributeDefinitions[1] attribute.ref_kind"), errors(changed));
        assertTrue(definitions.list("PERSON").getFirst().required());
    }

    private static ImportDocument definitions(List<AttributeDefinitionRow> rows) {
        return ImportDocument.of("2026-04-01", false, null, "attributeDefinitions", rows);
    }

    @Test
    void importsTheHierarchiesAndRenamesTheDefaultOne() {
        List<HierarchyRow> rows = List.of(new HierarchyRow("project", "プロジェクト"),
                new HierarchyRow("default", "組織図"));

        ImportReport first = service.apply(hierarchies(rows));
        ImportReport second = service.apply(hierarchies(rows));

        assertEquals(Map.of("hierarchies", new Counts(1, 1, 0)), first.counts());
        assertEquals(Map.of("hierarchies", new Counts(0, 0, 2)), second.counts());
        assertEquals("組織図", hierarchies.find("default").orElseThrow().name());
        assertEquals("fuhito/hierarchies/" + hierarchies.find("project").orElseThrow().id(),
                first.writes().getFirst().path());
    }

    private static OrgUnitRow ending(String externalId, String validTo, List<String> successorExternalIds,
            List<String> successorIds) {
        return new OrgUnitRow(null, externalId, null, "ja", ja(externalId), null, null, null, null,
                null, "2020-04-01", validTo, null, successorExternalIds, successorIds, null, null, null, null, null);
    }

    private static OrgUnitRow starting(String externalId, String validFrom) {
        return new OrgUnitRow(null, externalId, null, "ja", ja(externalId), null, null, null, null,
                null, validFrom, null, null);
    }

    @Test
    void endsAUnitWithSuccessorsThatComeLaterInTheDocument() {
        List<OrgUnitRow> rows = List.of(ending("OLD", "2026-04-01", List.of("NEW"), null),
                starting("NEW", "2026-04-01"));

        ImportReport first = service.apply(doc("2020-04-01", false, null, null, rows));
        ImportReport again = service.apply(doc("2020-04-01", false, null, null, rows));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("orgUnits", new Counts(2, 0, 0)), first.counts());
        assertEquals(Map.of("orgUnits", new Counts(0, 0, 2)), again.counts());
        String old = stored(ExternalId.Kind.ORG_UNIT, "import", "OLD");
        String successor = stored(ExternalId.Kind.ORG_UNIT, "import", "NEW");
        assertEquals(successor, orgUnits.successions(old).successors().getFirst().successorId());
        assertEquals("fuhito/org-units/" + old + "/succession", first.writes().getLast().path());

        ImportReport bad = service.apply(doc("2020-04-01", false, null, null, List.of(
                ending("OLD", "2026-04-01", null, List.of(old)),
                ending("A", null, List.of("NEW"), null),
                ending("B", "2026-04-01", List.of("NOPE"), null),
                ending("C", "2026-04-01", null, List.of("nope")))));

        assertEquals(List.of("orgUnits[1] invalid.required", "orgUnits[0] import.successors_differ",
                "orgUnits[2] import.successor_unknown", "orgUnits[3] import.id_unknown"), errors(bad));
    }

    private static OrgUnitRow linked(String externalId, String organizationAlias) {
        return new OrgUnitRow(null, externalId, null, "ja", ja(externalId), null, null, null, null,
                null, null, null, null, null, null, organizationAlias, null, null, null, null);
    }

    private static OrgUnitRow timed(String externalId, String timeZone, String dayStart) {
        return new OrgUnitRow(null, externalId, null, "ja", ja(externalId), null, null, null, null,
                null, null, null, null, null, null, null, timeZone, dayStart, null, null);
    }

    @Test
    void setsTheTimeOfAUnitAndKeepsWhatARowLeavesOut() {
        ImportReport first = importUnits("2026-04-01", timed("H", "Asia/Tokyo", "05:00"));
        ImportReport again = importUnits("2026-04-01", timed("H", null, null));
        ImportReport cleared = importUnits("2026-04-01", timed("H", null, ""));

        String h = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        assertEquals(List.of(), errors(first));
        assertEquals("fuhito/org-units/" + h + "/time-settings", first.writes().getLast().path());
        assertEquals(Map.of("orgUnits", new Counts(0, 0, 1)), again.counts());
        assertEquals(Map.of("orgUnits", new Counts(0, 1, 0)), cleared.counts());
        assertEquals("Asia/Tokyo", orgUnitStore.findUnit(h).orElseThrow().timeZone());
        assertEquals(null, orgUnitStore.findUnit(h).orElseThrow().dayStart());
        assertEquals(1, errors(importUnits("2026-04-01", timed("H", "Nowhere/City", null))).size());
    }

    @Test
    void linksAUnitToAnOrganizationByItsAliasAndAsksToManageIt() {
        ImportReport first = importUnits("2026-04-01", linked("H", "x-hospital"), linked("K", null));
        ImportReport again = importUnits("2026-04-01", linked("H", "x-hospital"), linked("K", null));
        ImportReport moved = importUnits("2026-04-01", linked("H", ""), linked("K", "x-hospital"),
                linked("N", "nope"));

        String h = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        String k = stored(ExternalId.Kind.ORG_UNIT, "import", "K");
        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("orgUnits", new Counts(0, 0, 2)), again.counts());
        assertEquals("fuhito/org-units/" + h + "/organization", first.writes().get(1).path());
        assertEquals(List.of("orgUnits[2] import.organization_unknown"), errors(moved));
        assertEquals(null, orgUnitStore.findUnit(h).orElseThrow().keycloakOrganizationId());
        assertEquals("org-x-hospital", orgUnitStore.findUnit(k).orElseThrow().keycloakOrganizationId());
        // The first link, then the old organization of the unlink, then the new one of the second link.
        assertEquals(List.of("org-x-hospital", "org-x-hospital", "org-x-hospital"), managed);
    }

    @Test
    void movesAUnitInAHierarchyAndReportsEveryBadRowOfParents() {
        importUnits("2026-04-01", unit("P", "P", null), unit("C", "C", null), unit("D", "D", null),
                unit("E", "E", null), unit("F", "F", null));
        String p = stored(ExternalId.Kind.ORG_UNIT, "import", "P");
        String c = stored(ExternalId.Kind.ORG_UNIT, "import", "C");
        List<OrgUnitParentRow> rows = List.of(
                new OrgUnitParentRow("default", null, "C", null, "P", null),
                new OrgUnitParentRow("default", null, null, p, null, null),
                new OrgUnitParentRow("default", null, "C", c, null, null),
                new OrgUnitParentRow("default", null, null, null, null, null),
                new OrgUnitParentRow("default", null, null, "nope", null, null),
                new OrgUnitParentRow("default", null, "NOPE", null, null, null),
                new OrgUnitParentRow("default", null, "C", null, null, null),
                new OrgUnitParentRow("default", null, "D", null, "P", p),
                new OrgUnitParentRow("default", null, "E", null, null, "nope"),
                new OrgUnitParentRow("default", null, "F", null, "NOPE", null));

        ImportReport report = service.apply(ImportDocument.of("2026-04-01", false, null, "orgUnitParents", rows));

        assertEquals(Map.of("orgUnitParents", new Counts(0, 1, 1)), report.counts());
        assertEquals(List.of("orgUnitParents[2] import.org_unit_twice", "orgUnitParents[3] invalid.required",
                "orgUnitParents[4] import.id_unknown", "orgUnitParents[5] import.org_unit_unknown",
                "orgUnitParents[6] import.duplicate_row", "orgUnitParents[7] import.parent_twice",
                "orgUnitParents[8] import.parent_id_unknown", "orgUnitParents[9] import.parent_unknown"),
                errors(report));
        assertEquals(p, orgUnits.get(c, "2026-04-01").parentId());
        assertEquals("fuhito/org-units/" + c + "/parent", report.writes().getFirst().path());
    }

    private static ImportDocument hierarchies(List<HierarchyRow> rows) {
        return ImportDocument.of("2026-04-01", false, null, "hierarchies", rows);
    }

    @Test
    void importsTheNamesOfAReferenceItemFromTheirDates() {
        List<RefItemRow> rows = List.of(
                new RefItemRow("JOB_TITLE", "m1", "en", ItemNames.english("Manager"), null, null, null, null, null, null, "2020-04-01"),
                new RefItemRow("JOB_TITLE", "m1", "en", ItemNames.english("Senior Manager"), null, null, null, null, null, null,
                        "2023-04-01"));

        ImportReport first = service.apply(doc("2026-04-01", false, null, rows, null));
        ImportReport again = service.apply(doc("2026-04-01", false, null, rows, null));

        assertEquals(Map.of("refItems", new Counts(1, 1, 0)), first.counts());
        assertEquals(Map.of("refItems", new Counts(0, 0, 2)), again.counts());
        RefItem item = refItems.findByCode(RefKind.JOB_TITLE, "m1").orElseThrow();
        assertEquals(List.of("2020-04-01..2023-04-01 Manager", "2023-04-01..null Senior Manager"),
                refItems.names(item.id()).stream()
                        .map(n -> n.validFrom() + ".." + n.validTo() + " " + n.names().primary().name())
                        .toList());

        // A row without a date changes the names from the effective date.
        service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("JOB_TITLE", "m1", "en", ItemNames.english("Head"), null, null, null)), null));

        assertEquals(List.of("Manager", "Senior Manager", "Head"),
                refItems.names(item.id()).stream().map(n -> n.names().primary().name()).toList());
        assertEquals(LocalDate.of(2026, 4, 1), refItems.names(item.id()).getLast().validFrom());
        assertEquals(List.of("refItems[0] invalid.date"), errors(service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("JOB_TITLE", "m1", "en", ItemNames.english("Head"), null, null, null, null, null, null, "soon")),
                null))));
    }

    @Test
    void changesAReferenceItemThatDiffers() {
        service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "hospital", "ja", ItemNames.japanese("病院"), null, null, null)), null));

        ImportReport report = service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "hospital", "ja", ItemNames.japanese("総合病院"), 3, null, false)), null));

        assertEquals(Map.of("refItems", new Counts(0, 1, 0)), report.counts());
        assertFalse(report.writes().getFirst().created());
        RefItem item = refItems.findByCode(RefKind.UNIT_TYPE, "hospital").orElseThrow();
        assertEquals(Localized.of("ja", new RefItemName("総合病院")), item.names());
        assertEquals(3, item.displayOrder());
        assertFalse(item.active());
    }

    @Test
    void reportsEveryBadReferenceItemAndStillAppliesTheGoodOnes() {
        List<RefItemRow> rows = new ArrayList<>();
        rows.add(new RefItemRow("UNIT_TYPE", "ok", "en", ItemNames.english("OK"), null, null, null));
        rows.add(new RefItemRow("NOPE", "x", "en", ItemNames.english("x"), null, null, null));
        rows.add(null);
        rows.add(new RefItemRow(null, "x", "en", ItemNames.english("x"), null, null, null));
        rows.add(new RefItemRow("UNIT_TYPE", "bad code", "en", ItemNames.english("x"), null, null, null));
        rows.add(new RefItemRow("UNIT_TYPE", "blank", "en", ItemNames.english(" "), null, null, null));

        ImportReport report = service.apply(doc("2026-04-01", false, null, rows, null));

        assertEquals(List.of("refItems[1] invalid.kind", "refItems[2] invalid.required",
                "refItems[3] invalid.required", "refItems[4] invalid.code", "refItems[5] invalid.name"),
                errors(report));
        assertEquals(Map.of("refItems", new Counts(1, 0, 0)), report.counts());
    }

    @Test
    void importsOrgUnitsInAnyOrder() {
        ImportReport report = importUnits("2026-04-01",
                unit("W1", "Ward", "N"),
                unit("N", "Nursing", "H"),
                unit("H", "Hospital", null));

        assertEquals(List.of(), errors(report));
        assertEquals(Map.of("orgUnits", new Counts(3, 0, 0)), report.counts());
        assertEquals(List.of("Hospital", "Nursing < Hospital", "Ward < Nursing"), tree("2026-04-01"));
        assertEquals(List.of("Hospital", "Nursing", "Ward"), report.writes().stream()
                .map(w -> ((ImportReport.Write.OfOrgUnit) w).state().version().names().primary().name()).toList());
        assertEquals("import", orgUnitStore.findUnit(stored(ExternalId.Kind.ORG_UNIT, "import", "H")).orElseThrow().source());
    }

    @Test
    void theSameDocumentTwiceChangesNothing() {
        OrgUnitRow[] rows = {
            new OrgUnitRow("hr", "H", "hq", "EN", Map.of("en", new OrgUnitName(" Hospital ", "H", null),
                    "JA", new OrgUnitName("病院", "病", "びょういん")), null, null, null, 2, Map.of("beds", 100), null,
                    null, null),
            new OrgUnitRow("hr", "N", null, "ja", ja("Nursing"), null, "H", null, null, null, null, null, null)};
        importUnits("2026-04-01", rows);

        ImportReport again = importUnits("2026-04-01", rows);
        ImportReport later = importUnits("2027-04-01", rows);

        assertEquals(Map.of("orgUnits", new Counts(0, 0, 2)), again.counts());
        assertEquals(Map.of("orgUnits", new Counts(0, 0, 2)), later.counts());
        assertEquals(List.of(), later.writes());
        assertEquals(1, orgUnitStore.versions(stored(ExternalId.Kind.ORG_UNIT, "hr", "H")).size());
    }

    @Test
    void aChangedDocumentRenamesMovesAndEndsFromTheEffectiveDate() {
        importUnits("2026-04-01", unit("H", "Hospital", null), unit("A", "A", "H"), unit("B", "B", "H"),
                unit("C", "C", "A"));

        ImportReport report = importUnits("2027-04-01",
                unit("H", "General Hospital", null),
                unit("C", "C", "B"),
                new OrgUnitRow(null, "A", null, "ja", ja("A"), null, "H", null, null, null, null,
                        "2027-04-01", null),
                unit("B", "B", "H"),
                unit("D", "D", "B"));

        assertEquals(List.of(), errors(report));
        assertEquals(Map.of("orgUnits", new Counts(1, 3, 1)), report.counts());
        assertEquals(List.of("Hospital", "A < Hospital", "C < A", "B < Hospital"), tree("2027-03-31"));
        assertEquals(List.of("General Hospital", "B < General Hospital", "C < B", "D < B"), tree("2027-04-01"));
        assertEquals(List.of("", "/parent", "/end", ""), report.writes().stream()
                .map(w -> w.path().substring("fuhito/org-units/".length() + 36)).toList());
        // The same document again finds nothing to do, the ended unit included.
        assertEquals(new Counts(0, 0, 4), importUnits("2027-03-31",
                unit("H", "Hospital", null), unit("B", "B", "H"), unit("C", "C", "A"),
                new OrgUnitRow(null, "A", null, "ja", ja("A"), null, "H", null, null, null, null,
                        "2027-04-01", null)).counts().get("orgUnits"));
    }

    @Test
    void aNewUnitMayHaveItsOwnStartDateAndAnEndDate() {
        ImportReport report = importUnits("2026-04-01",
                new OrgUnitRow(null, "OLD", null, "ja", ja("Old"), null, null, null, null, null, "2001-04-01",
                        "2027-04-01", null));

        assertEquals(List.of(), errors(report));
        var versions = orgUnitStore.versions(stored(ExternalId.Kind.ORG_UNIT, "import", "OLD"));
        assertEquals(LocalDate.of(2001, 4, 1), versions.getFirst().period().from());
        assertEquals(LocalDate.of(2027, 4, 1), versions.getFirst().period().to());
        assertEquals(2, report.writes().size());
    }

    @Test
    void theSourceSeparatesExternalIds() {
        ImportReport report = service.apply(doc("2026-04-01", false, "hr-a", null, List.of(
                unit("1", "From A", null),
                new OrgUnitRow("hr-b", "1", null, "ja", ja("From B"), null, null, null, null, null, null,
                        null, null),
                new OrgUnitRow("hr-b", "2", null, "ja", ja("Child of B"), null, "1", null, null, null, null,
                        null, null))));

        assertEquals(List.of(), errors(report));
        assertEquals(List.of("From A", "From B", "Child of B < From B"), tree("2026-04-01"));
        assertEquals("hr-a", orgUnitStore.findUnit(stored(ExternalId.Kind.ORG_UNIT, "hr-a", "1")).orElseThrow().source());
    }

    @Test
    void aParentMayBeStoredAlready() {
        importUnits("2026-04-01", unit("H", "Hospital", null));

        assertEquals(List.of(), errors(importUnits("2026-10-01", unit("N", "Nursing", "H"))));
        assertEquals(List.of("Hospital", "Nursing < Hospital"), tree("2026-10-01"));
    }

    @Test
    void reportsEveryBadOrgUnitRow() {
        List<OrgUnitRow> rows = new ArrayList<>();
        rows.add(unit("OK", "OK", null));
        rows.add(unit(null, "No ID", null));
        rows.add(null);
        rows.add(unit("OK", "Duplicate", null));
        rows.add(unit("ORPHAN", "Orphan", "NOPE"));
        rows.add(unit("BLANK", " ", null));
        rows.add(unit("CHILD", "Child of blank", "BLANK"));
        rows.add(unit("X", "X", "Y"));
        rows.add(unit("Y", "Y", "X"));
        rows.add(new OrgUnitRow("bad source", "S", null, "ja", ja("S"), null, null, null, null, null, null,
                null, null));
        rows.add(new OrgUnitRow(null, "TYPE", null, "ja", ja("T"), "nope", null, null, null, null, null,
                null, null));
        rows.add(new OrgUnitRow(null, "TWICE", null, "ja", ja("T"), null, "OK", "id", null, null, null,
                null, null));
        rows.add(new OrgUnitRow(null, "NO-ID", null, "ja", ja("T"), null, null, "nope", null, null, null,
                null, null));

        ImportReport report = service.apply(doc("2026-04-01", false, null, null, rows));

        assertEquals(List.of(
                "orgUnits[1] invalid.required",
                "orgUnits[2] invalid.required",
                "orgUnits[3] import.duplicate_row",
                "orgUnits[4] import.parent_unknown",
                "orgUnits[5] invalid.name",
                "orgUnits[6] import.parent_failed",
                "orgUnits[8] import.parent_cycle",
                "orgUnits[7] import.parent_failed",
                "orgUnits[9] invalid.code",
                "orgUnits[10] org_unit.unit_type_unknown",
                "orgUnits[11] import.parent_twice",
                "orgUnits[12] import.parent_id_unknown"), errors(report));
        assertEquals(new Counts(1, 0, 0), report.counts().get("orgUnits"));
        assertEquals(Map.of("parentExternalId", "NOPE"), report.errors().get(3).error().params());
    }

    @Test
    void aRowCanNameAStoredUnitByItsId() {
        importUnits("2026-04-01", unit("H", "Hospital", null));
        String id = stored(ExternalId.Kind.ORG_UNIT, "import", "H");

        ImportReport renamed = importUnits("2026-05-01", new OrgUnitRow(null, null, null, "ja", ja("Main hospital"), null, null, null, null, null, null, null, id));

        assertEquals(List.of(), errors(renamed));
        assertEquals(new Counts(0, 1, 0), renamed.counts().get("orgUnits"));
        assertEquals("Main hospital", orgUnits.get(id, "2026-05-01").version().names().primary().name());
        assertEquals(List.of("orgUnits[0] import.id_unknown", "orgUnits[1] import.id_mismatch"),
                errors(importUnits("2026-05-01",
                        new OrgUnitRow(null, null, null, "ja", ja("X"), null, null, null, null, null,
                                null, null, "nope"),
                        new OrgUnitRow(null, "OTHER", null, "ja", ja("X"), null, null, null, null, null,
                                null, null, id))));
    }

    @Test
    void theSameDocumentAgainChangesNothingForRowsThatDoNotHoldOnItsDate() {
        importTwoUnits();
        OrgUnitRow ending = new OrgUnitRow(null, "W", null, "ja", ja("Ward"), null, "H", null, null, null,
                null, "2027-04-01", null);
        OrgUnitRow later = new OrgUnitRow(null, "L", null, "ja", ja("Lab"), null, "H", null, null, null,
                "2027-05-01", null, null);
        EmploymentRow hired = new EmploymentRow(null, "E9", "doctor", null, null, null, null, null, null, null, null,
                "2027-05-01", null, null, null, null, null, null, null);
        AssignmentRow placed = new AssignmentRow(null, "A9", "E9", "L", null, null, null, null, true, null, null,
                null, "2027-05-01", null);
        ImportDocument document = new ImportDocument("2027-04-01", false, null, null, List.of(ending, later),
                List.of(hired), List.of(placed));
        assertEquals(List.of(), errors(service.apply(document)));

        ImportReport again = service.apply(document);

        assertEquals(List.of(), errors(again));
        assertEquals(List.of(), again.writes());

        // A change of a unit that starts later still needs a date on which the unit exists.
        ImportReport renamed = service.apply(new ImportDocument("2027-04-01", false, null, null,
                List.of(new OrgUnitRow(null, "L", null, "ja", ja("Laboratory"), null, "H", null, null, null,
                        null, null, null)), null, null));
        assertEquals(List.of("orgUnits[0] org_unit.not_valid_on"), errors(renamed));
    }

    @Test
    void anExistingUnitMustExistOnTheEffectiveDate() {
        importUnits("2026-04-01", unit("H", "Hospital", null));

        assertEquals(List.of("orgUnits[0] org_unit.not_valid_on"),
                errors(importUnits("2026-03-01", unit("H", "Renamed", null))));
    }

    private static EmploymentRow employment(String externalId, String username, String status, String validTo,
            String endReason) {
        return new EmploymentRow(null, externalId, username, null, null, null, null, status, null, null, null,
                validTo, endReason);
    }

    private static AssignmentRow assignment(String externalId, String employment, String orgUnit, boolean primary,
            String validTo) {
        return new AssignmentRow(null, externalId, employment, orgUnit, null, null, null, null, primary, null, null,
                null, null, validTo);
    }

    private ImportReport importPeople(String effectiveDate, List<EmploymentRow> employmentRows,
            List<AssignmentRow> assignmentRows) {
        return service.apply(new ImportDocument(effectiveDate, false, null, null, null, employmentRows,
                assignmentRows));
    }

    @Test
    void importsPeopleAlsoWithNoUserAndNamesThemFromEmployments() {
        importTwoUnits();
        // The nurse has a person already, made for the user by an earlier employment.
        importPeople("2026-04-01", List.of(employment("E0", "nurse", null, null, null)), null);
        List<PersonRow> rows = List.of(
                new PersonRow(null, "P1", null, null, "ja", Map.of("ja", new PersonName("山田 花子", "やまだ はなこ"),
                        "en", new PersonName("Hanako Yamada", null)), Map.of("note", "retired"), null),
                new PersonRow(null, "P2", "nurse", null, null, null, null, null));

        ImportReport first = service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null, rows));

        assertEquals(new Counts(1, 1, 0), first.counts().get("people"));
        Person retired = personStore.find(stored(ExternalId.Kind.PERSON, "import", "P1")).orElseThrow();
        assertEquals(null, retired.userId());
        assertEquals("やまだ はなこ", retired.names().primary().phoneticName());
        assertEquals(personStore.findByUser(users.findIdByUsername("nurse").orElseThrow()).orElseThrow().id(),
                stored(ExternalId.Kind.PERSON, "import", "P2"));
        assertEquals(new Counts(0, 0, 2), service.apply(new ImportDocument("2026-04-01", false, null, null, null,
                null, null, rows)).counts().get("people"));

        // An employment row names the person with no user.
        EmploymentRow old = new EmploymentRow(null, "E9", null, null, null, null, null, null, null, null, null,
                "1990-04-01", "2000-04-01", null, null, "P1", null, null, null);
        assertEquals(List.of(), service.apply(new ImportDocument("2026-04-01", false, null, null, null, List.of(old),
                null)).errors());
        assertEquals(retired.id(), employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E9")).orElseThrow().personId());
        // A row for that stored employment may name nobody.
        EmploymentRow again = new EmploymentRow(null, "E9", null, null, null, null, null, null, null, null, null,
                null, "2000-04-01", null, null, null, null, null, null);
        assertEquals(new Counts(0, 0, 1), service.apply(new ImportDocument("2026-04-01", false, null, null, null,
                List.of(again), null)).counts().get("employments"));

        EmploymentRow unknown = new EmploymentRow(null, "E8", null, null, null, null, null, null, null, null, null,
                "1990-04-01", null, null, null, "P7", null, null, null);
        assertEquals("import.person_unknown", service.apply(new ImportDocument("2026-04-01", false, null, null, null,
                List.of(unknown), null)).errors().getFirst().error().code());
        EmploymentRow mismatch = new EmploymentRow(null, "E7", "nurse", null, null, null, null, null, null, null,
                null, "1990-04-01", null, null, null, "P1", null, null, null);
        assertEquals("import.user_mismatch", service.apply(new ImportDocument("2026-04-01", false, null, null,
                null, List.of(mismatch), null)).errors().getFirst().error().code());
        PersonRow renamed = new PersonRow(null, "P1", null, null, "ja", japanese("山田 華子"), null, null);
        assertEquals(new Counts(0, 1, 0), service.apply(new ImportDocument("2026-04-01", false, null, null, null,
                null, null, List.of(renamed))).counts().get("people"));
    }

    private static EmploymentRow byIds(String externalId, String personId, String employerId, String grade,
            String id) {
        return new EmploymentRow(null, externalId, null, null, null, null, grade, null, null, null, null,
                "2020-04-01", null, null, null, null, personId, employerId, id);
    }

    private ImportReport importEmployments(EmploymentRow... rows) {
        return service.apply(new ImportDocument("2026-04-01", false, null, null, null, List.of(rows), null));
    }

    @Test
    void aRowNamesAStoredEmploymentItsPersonAndItsEmployerByTheirIds() {
        importTwoUnits();
        refItems.insert(new RefItem("g", RefKind.GRADE, "g2", ItemNames.of("x"), 0, Map.of(), true));
        String hospital = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        PersonService personService = new PersonService(personStore, externalIdStore, users, id -> false);
        String taro = personService.create(new PersonValues(null, "ja", japanese("Taro"), null, null, null)).person().id();

        assertEquals(List.of(), errors(importEmployments(byIds("E1", taro, hospital, null, null))));
        Employment stored = employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1")).orElseThrow();
        assertEquals(taro, stored.personId());
        assertEquals(hospital, stored.employerId());

        ImportReport changed = importEmployments(byIds(null, taro, hospital, "g2", stored.id()));
        assertEquals(List.of(), errors(changed));
        assertEquals(new Counts(0, 1, 0), changed.counts().get("employments"));
        assertEquals(new Counts(0, 0, 1), importEmployments(byIds("E1", null, null, "g2", stored.id())).counts()
                .get("employments"));

        String hanako = personService.create(new PersonValues(null, "ja", japanese("Hanako"), null, null, null)).person()
                .id();
        EmploymentRow twice = new EmploymentRow(null, "E2", null, null, null, null, null, null, null, null, null,
                null, null, null, "H", "P1", taro, hospital, null);
        assertEquals(List.of("employments[0] import.user_mismatch"),
                errors(importEmployments(byIds(null, hanako, null, null, stored.id()))));
        assertEquals(List.of("employments[0] import.id_unknown", "employments[1] import.id_mismatch",
                "employments[2] import.id_unknown", "employments[3] import.id_unknown",
                "employments[4] import.person_twice", "employments[5] import.duplicate_row"),
                errors(importEmployments(byIds(null, null, null, null, "nope"),
                        byIds("OTHER", null, null, null, stored.id()),
                        byIds("E3", "nope", hospital, null, null),
                        byIds("E4", taro, "nope", null, null),
                        twice,
                        byIds(null, null, null, null, "nope"))));
        EmploymentRow employerTwice = new EmploymentRow(null, "E2", null, null, null, null, null, null, null, null,
                null, null, null, null, "H", null, taro, hospital, null);
        assertEquals(List.of("employments[0] import.employer_twice"), errors(importEmployments(employerTwice)));
    }

    private static AssignmentRow placement(String externalId, String employmentExternalId, String employmentId,
            String orgUnitExternalId, String orgUnitId, Integer displayOrder, String id) {
        return new AssignmentRow(null, externalId, employmentExternalId, orgUnitExternalId, null, null, null, null,
                null, null, displayOrder, null, "2026-04-01", null, employmentId, orgUnitId, id);
    }

    private ImportReport importAssignments(AssignmentRow... rows) {
        return service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, List.of(rows)));
    }

    @Test
    void aRowNamesAStoredAssignmentItsEmploymentAndItsOrgUnitByTheirIds() {
        importTwoUnits();
        String hospital = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        String ward = stored(ExternalId.Kind.ORG_UNIT, "import", "W");
        String taro = new PersonService(personStore, externalIdStore, users, id -> false)
                .create(new PersonValues(null, "ja", japanese("Taro"), null, null, null)).person().id();
        importEmployments(byIds("E1", taro, null, null, null));
        String employment = stored(ExternalId.Kind.EMPLOYMENT, "import", "E1");

        assertEquals(List.of(), errors(importAssignments(placement("A1", null, employment, null, ward, null, null))));
        String assignment = stored(ExternalId.Kind.ASSIGNMENT, "import", "A1");
        assertEquals(ward, assignmentStore.rows(assignment).getFirst().orgUnitId());

        // A row with the ID alone keeps the employment and the org unit.
        ImportReport changed = importAssignments(placement(null, null, null, null, null, 3, assignment));
        assertEquals(List.of(), errors(changed));
        assertEquals(new Counts(0, 1, 0), changed.counts().get("assignments"));
        assertEquals(3, assignmentStore.rows(assignment).getLast().displayOrder());
        assertEquals(new Counts(0, 0, 1), importAssignments(placement("A1", "E1", null, null, ward, 3, assignment))
                .counts().get("assignments"));

        assertEquals(List.of("assignments[0] import.id_unknown", "assignments[1] import.id_mismatch",
                "assignments[2] import.employment_twice", "assignments[3] import.org_unit_twice",
                "assignments[4] import.id_unknown", "assignments[5] import.id_unknown",
                "assignments[6] invalid.required"),
                errors(importAssignments(placement(null, null, null, null, null, null, "nope"),
                        placement("OTHER", null, null, null, null, null, assignment),
                        placement("A2", "E1", employment, null, ward, null, null),
                        placement("A3", null, employment, "W", ward, null, null),
                        placement("A4", null, "nope", null, ward, null, null),
                        placement("A5", null, employment, null, "nope", null, null),
                        placement("A6", null, null, null, ward, null, null))));
        assertEquals(List.of("assignments[0] import.assignment_mismatch"),
                errors(importAssignments(placement(null, null, null, null, hospital, null, assignment))));
    }

    private static ReportingLineRow line(String id, String assignment, String managerExternalId,
            String managerUsername, String validTo) {
        return new ReportingLineRow(id, null, assignment, null, managerExternalId, null, managerUsername, null, null,
                validTo);
    }

    private ImportReport importLines(String effectiveDate, ReportingLineRow... rows) {
        return service.apply(ImportDocument.of(effectiveDate, false, null, "reportingLines", List.of(rows)));
    }

    @Test
    void importsReportingLinesAndRejectsASecondSolidLine() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", true, null)));
        service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null,
                List.of(new PersonRow(null, "P3", null, null, "ja", japanese("Chief"), null, null))));

        ImportReport first = importLines("2026-04-01", line(null, "A1", null, "doctor", null));
        ImportReport again = importLines("2026-04-01", line(null, "A1", null, "doctor", null));
        ImportReport second = importLines("2026-05-01", line(null, "A1", "P3", null, null));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("reportingLines", new Counts(1, 0, 0)), first.counts());
        assertEquals(Map.of("reportingLines", new Counts(0, 0, 1)), again.counts());
        String doctor = lineStore.lines.getFirst().id();
        assertEquals(List.of("reportingLines[0] reporting_line.solid_exists"), errors(second));
        assertEquals(doctor, second.errors().getFirst().error().params().get("lineId"));
        assertEquals(List.of(), second.writes());

        // The row that ends the stored line can come after the new line, and the same file loads again.
        ReportingLineRow[] change = {line(null, "A1", "P3", null, null), line(doctor, null, null, null, "2026-05-01")};
        ImportReport next = importLines("2026-05-01", change);
        ImportReport nextAgain = importLines("2026-05-01", change);

        assertEquals(List.of(), errors(next));
        assertEquals(Map.of("reportingLines", new Counts(1, 1, 0)), next.counts());
        assertEquals(Map.of("reportingLines", new Counts(0, 0, 2)), nextAgain.counts());
        List<ReportingLine> stored = lineStore.lines.stream()
                .sorted(java.util.Comparator.comparing(l -> l.period().from()))
                .toList();
        assertEquals("2026-05-01", stored.getFirst().period().to().toString());
        String chiefLine = stored.getLast().id();
        assertEquals(stored(ExternalId.Kind.PERSON, "import", "P3"), stored.getLast().managerPersonId());
        assertEquals("fuhito/reporting-lines/" + doctor + "/end", next.writes().getFirst().path());

        ImportReport back = importLines("2026-06-01", line(chiefLine, null, null, null, "2026-06-01"),
                line(null, "A1", null, "doctor", null));
        assertEquals(List.of(), errors(back));
        assertEquals(Map.of("reportingLines", new Counts(1, 1, 0)), back.counts());
        assertEquals(3, lineStore.lines.size());
        assertEquals(List.of("reportingLines[0] import.assignment_unknown", "reportingLines[1] import.manager_twice",
                "reportingLines[2] import.username_unknown", "reportingLines[3] import.id_unknown",
                "reportingLines[4] import.person_unknown", "reportingLines[5] invalid.required"),
                errors(importLines("2026-05-01", line(null, "NOPE", "P3", null, null),
                        line(null, "A1", "P3", "doctor", null), line(null, "A1", null, "nobody", null),
                        line("nope", null, null, null, null), line(null, "A1", "NOPE", null, null),
                        line(null, "A1", null, null, null))));
        String a1 = stored(ExternalId.Kind.ASSIGNMENT, "import", "A1");
        String chief = stored(ExternalId.Kind.PERSON, "import", "P3");
        assertEquals(List.of("reportingLines[0] import.assignment_twice", "reportingLines[1] import.id_mismatch",
                "reportingLines[3] import.duplicate_row"),
                errors(importLines("2026-05-01",
                        new ReportingLineRow(null, null, "A1", a1, "P3", null, null, null, null, null),
                        new ReportingLineRow(chiefLine, null, null, null, null, null, "doctor", null, null, null),
                        new ReportingLineRow(null, null, null, a1, null, chief, null, null, null, null),
                        new ReportingLineRow(null, null, null, a1, null, chief, null, null, null, null))));
    }

    private static UnitRoleRow role(String id, String employment, String orgUnit, String role, Boolean below,
            String validTo) {
        return new UnitRoleRow(id, null, employment, null, orgUnit, null, role, below, null, validTo);
    }

    private ImportReport importRoles(String effectiveDate, UnitRoleRow... rows) {
        return service.apply(ImportDocument.of(effectiveDate, false, null, "unitRoles", List.of(rows)));
    }

    @Test
    void importsRolesAndKeepsWhetherARoleCoversTheUnitsBelow() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)), null);
        refItems.insert(new RefItem("approver-id", RefKind.UNIT_ROLE.name(), "approver", ItemNames.of("承認者"), 0,
                Map.of(), true, false, false, false, 1, null, null));

        ImportReport first = importRoles("2026-04-01", role(null, "E1", "H", "approver", null, null));
        ImportReport again = importRoles("2026-04-01", role(null, "E1", "H", "approver", false, null));
        ImportReport wider = importRoles("2026-05-01", role(null, "E1", "H", "approver", true, null));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("unitRoles", new Counts(1, 0, 0)), first.counts());
        assertEquals(Map.of("unitRoles", new Counts(0, 0, 1)), again.counts());
        String narrow = roleStore.roles.getFirst().id();
        assertEquals(List.of("unitRoles[0] unit_role.overlap"), errors(wider));
        assertEquals(narrow, wider.errors().getFirst().error().params().get("id"));
        assertEquals(List.of(), wider.writes());
        assertEquals(List.of("unitRoles[0] import.unit_role_mismatch"),
                errors(importRoles("2026-05-01", role(narrow, null, null, null, true, null))));

        // The row that ends the stored role can come after the new role, and the same file loads again.
        UnitRoleRow[] change = {role(null, "E1", "H", "approver", true, null),
                role(narrow, null, null, null, null, "2026-05-01")};
        ImportReport changed = importRoles("2026-05-01", change);
        ImportReport changedAgain = importRoles("2026-05-01", change);

        assertEquals(List.of(), errors(changed));
        assertEquals(Map.of("unitRoles", new Counts(1, 1, 0)), changed.counts());
        assertEquals(Map.of("unitRoles", new Counts(0, 0, 2)), changedAgain.counts());
        List<UnitRole> stored = roleStore.roles.stream()
                .sorted(java.util.Comparator.comparing(r -> r.period().from()))
                .toList();
        assertEquals("2026-05-01", stored.getFirst().period().to().toString());
        assertTrue(stored.getLast().includesDescendants());
        String wide = stored.getLast().id();
        assertEquals("fuhito/unit-roles/" + narrow + "/end", changed.writes().getFirst().path());

        ImportReport back = importRoles("2026-06-01", role(wide, null, null, null, null, "2026-06-01"),
                role(null, "E1", "H", "approver", false, null));
        assertEquals(List.of(), errors(back));
        assertEquals(Map.of("unitRoles", new Counts(1, 1, 0)), back.counts());
        assertEquals(3, roleStore.roles.size());
        assertEquals(List.of("unitRoles[0] import.employment_unknown", "unitRoles[1] import.org_unit_unknown",
                "unitRoles[2] import.id_unknown", "unitRoles[3] invalid.required",
                "unitRoles[4] import.id_mismatch", "unitRoles[6] import.duplicate_row"),
                errors(importRoles("2026-05-01", role(null, "NOPE", "H", "approver", null, null),
                        role(null, "E1", "NOPE", "approver", null, null), role("nope", null, null, null, null, null),
                        role(null, "E1", "W", null, null, null),
                        role(wide, null, "W", null, true, null),
                        role(null, "E1", "H", "approver", true, null), role(null, "E1", "H", "approver", true, null))));
        String e1 = stored(ExternalId.Kind.EMPLOYMENT, "import", "E1");
        String h = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        assertEquals(List.of("unitRoles[0] import.employment_twice", "unitRoles[1] import.org_unit_twice"),
                errors(importRoles("2026-05-01",
                        new UnitRoleRow(null, null, "E1", e1, null, h, "approver", true, null, null),
                        new UnitRoleRow(null, null, null, e1, "H", h, "approver", true, null, null),
                        new UnitRoleRow(null, null, null, e1, null, h, "approver", true, null, null))));
    }

    private static QualificationRow qualification(String id, String externalId, String person, String username,
            String number, String validTo) {
        return new QualificationRow(id, null, externalId, person, null, username, "license", number, "2020-04-01",
                validTo, null);
    }

    private ImportReport importQualifications(QualificationRow... rows) {
        return service.apply(ImportDocument.of("2026-04-01", false, null, "qualifications", List.of(rows)));
    }

    @Test
    void importsQualificationsAndReplacesAStoredOne() {
        refItems.insert(new RefItem("license-id", RefKind.QUALIFICATION.name(), "license", ItemNames.of("免許"), 0,
                Map.of(), true, false, false, false, 1, null, null));
        service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null,
                List.of(new PersonRow(null, "P1", null, null, "ja", japanese("Ann"), null, null),
                        new PersonRow(null, "P2", null, null, "ja", japanese("Ben"), null, null))));

        ImportReport first = importQualifications(qualification(null, "Q1", "P1", null, "123", null),
                qualification(null, "Q2", null, "nurse", null, null));
        ImportReport again = importQualifications(qualification(null, "Q1", "P1", null, "123", null));
        ImportReport changed = importQualifications(qualification(null, "Q1", null, null, "456", "2030-04-01"));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("qualifications", new Counts(2, 0, 0)), first.counts());
        assertEquals(Map.of("qualifications", new Counts(0, 0, 1)), again.counts());
        assertEquals(Map.of("qualifications", new Counts(0, 1, 0)), changed.counts());
        Qualification q1 = qualificationStore.find(stored(ExternalId.Kind.QUALIFICATION, "import", "Q1")).orElseThrow();
        assertEquals("456", q1.number());
        assertEquals("2030-04-01", q1.period().to().toString());
        assertEquals("fuhito/qualifications/" + q1.id(), changed.writes().getFirst().path());
        assertEquals(personStore.findByUser("user-nurse").orElseThrow().id(),
                qualificationStore.find(stored(ExternalId.Kind.QUALIFICATION, "import", "Q2")).orElseThrow().personId());

        assertEquals(List.of("qualifications[0] invalid.required", "qualifications[1] import.id_unknown",
                "qualifications[2] import.person_unknown", "qualifications[3] import.person_ways",
                "qualifications[4] import.id_mismatch", "qualifications[5] import.id_mismatch",
                "qualifications[6] import.duplicate_row", "qualifications[7] import.username_unknown",
                "qualifications[8] import.id_unknown"),
                errors(importQualifications(qualification(null, null, "P1", null, null, null),
                        qualification("nope", null, null, null, null, null),
                        qualification(null, "Q3", "NOPE", null, null, null),
                        qualification(null, "Q4", "P1", "nurse", null, null),
                        qualification(null, "Q1", "P2", null, null, null),
                        qualification(q1.id(), "Q2", null, null, null, null),
                        qualification(null, "Q1", null, null, null, null),
                        qualification(null, "Q5", null, "nobody", null, null),
                        new QualificationRow(null, null, "Q6", null, "nope", null, "license", null, null, null,
                                null))));
    }

    private ImportReport importExternalIds(ExternalIdRow... rows) {
        return service.apply(ImportDocument.of("2026-04-01", false, null, "externalIds", List.of(rows)));
    }

    @Test
    void importsTheIdsOfOtherSystemsAndRemovesOneWithAnEmptyText() {
        externalIdStore.resource(ExternalId.Kind.ORG_UNIT, "u-1", "hr");
        externalIdStore.insert(new ExternalId("row-h", ExternalId.Kind.ORG_UNIT, "u-1", "hr", "H", 1));

        ImportReport first = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-1"));
        ImportReport again = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-1"));
        ImportReport changed = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-2"));
        ImportReport removed = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", ""),
                new ExternalIdRow("ORG_UNIT", "u-1", "ledger", ""));

        assertEquals(Map.of("externalIds", new Counts(1, 0, 0)), first.counts());
        assertEquals("fuhito/external-ids/" + first.writes().getFirst().resourceId(), first.writes().getFirst().path());
        assertEquals(Map.of("externalIds", new Counts(0, 0, 1)), again.counts());
        assertEquals(Map.of("externalIds", new Counts(0, 1, 0)), changed.counts());
        assertEquals(Map.of("externalIds", new Counts(0, 1, 1)), removed.counts());
        assertTrue(removed.writes().getFirst().deleted());
        assertEquals(List.of("H"), externalIdStore.rows.stream().map(ExternalId::externalId).toList());
        // A row of the system that made the resource replaces its ID too.
        assertEquals(List.of("externalIds[1] import.duplicate_row", "externalIds[3] external_id.resource_unknown",
                "externalIds[4] invalid.required"),
                errors(importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-3"),
                        new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-4"),
                        new ExternalIdRow("ORG_UNIT", "u-1", "hr", "X"),
                        new ExternalIdRow("ORG_UNIT", "nope", "payroll", "P-5"),
                        new ExternalIdRow("ORG_UNIT", "u-1", "ledger", null))));
    }

    private static ChangeOrderRow order(String id, String number, String title, String approverUserId,
            String approverUsername) {
        return new ChangeOrderRow(id, number, null, title, null, null, null, approverUserId, approverUsername, null,
                null, null);
    }

    private ImportReport importOrders(ChangeOrderRow... rows) {
        return service.apply(ImportDocument.of("2026-04-01", false, null, "changeOrders", List.of(rows)));
    }

    @Test
    void importsChangeOrdersByNumberAndReplacesAStoredOne() {
        ImportReport first = importOrders(order(null, "2026-01", "異動", null, "doctor"));
        ImportReport again = importOrders(order(null, "2026-01", "異動", null, "doctor"));
        ImportReport changed = importOrders(order(null, "2026-01", "人事異動", "user-doctor", null));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("changeOrders", new Counts(1, 0, 0)), first.counts());
        assertEquals(Map.of("changeOrders", new Counts(0, 0, 1)), again.counts());
        assertEquals(Map.of("changeOrders", new Counts(0, 1, 0)), changed.counts());
        ChangeOrder stored = orderStore.findByNumber("2026-01").orElseThrow();
        assertEquals("人事異動", stored.title());
        assertEquals("user-doctor", stored.approverUserId());
        assertEquals("2026-04-01", stored.effectiveDate().toString());
        assertEquals("fuhito/change-orders/" + stored.id(), changed.writes().getFirst().path());
        assertEquals(List.of("changeOrders[0] invalid.required", "changeOrders[1] import.id_unknown",
                "changeOrders[2] import.user_twice", "changeOrders[3] import.username_unknown",
                "changeOrders[5] import.duplicate_row"),
                errors(importOrders(order(null, null, "X", null, null), order("nope", null, "X", null, null),
                        order(null, "2026-02", "X", "user-doctor", "doctor"), order(null, "2026-03", "X", null, "nobody"),
                        order(stored.id(), null, "人事異動", "user-doctor", null),
                        order(stored.id(), "2026-01", "人事異動", "user-doctor", null))));
    }

    private static EmploymentRow continuing(String externalId, String continuesExternalId, String continuesId) {
        return new EmploymentRow(null, externalId, "nurse", null, null, null, null, null, null, null, null,
                "2026-04-01", null, null, null, null, null, null, null, continuesExternalId, continuesId);
    }

    private static AssignmentRow continuingAssignment(String externalId, String employment,
            String continuesExternalId) {
        return new AssignmentRow(null, externalId, employment, "W", null, null, null, null, true, null, null, null,
                "2026-04-01", null, null, null, null, continuesExternalId, null, null, null);
    }

    @Test
    void linksANewEmploymentAndItsAssignmentToTheOnesTheyContinue() {
        importUnits("2020-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"));
        assertEquals(List.of(), errors(importPeople("2020-04-01",
                List.of(employment("E1", "nurse", null, "2026-04-01", null)),
                List.of(assignment("A1", "E1", "W", true, null)))));

        ImportReport first = importPeople("2026-04-01", List.of(continuing("E2", "E1", null)),
                List.of(continuingAssignment("A2", "E2", "A1")));
        ImportReport again = importPeople("2026-04-01", List.of(continuing("E2", "E1", null)),
                List.of(continuingAssignment("A2", "E2", "A1")));

        assertEquals(List.of(), errors(first));
        Employment e1 = employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1")).orElseThrow();
        assertEquals(e1.id(), employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E2")).orElseThrow()
                .continuesEmploymentId());
        String a1 = stored(ExternalId.Kind.ASSIGNMENT, "import", "A1");
        String a2 = stored(ExternalId.Kind.ASSIGNMENT, "import", "A2");
        assertEquals(a1, assignmentStore.rows(a2).getFirst().continuesAssignmentId());
        assertEquals(new Counts(0, 0, 1), again.counts().get("employments"));
        assertEquals(new Counts(0, 0, 1), again.counts().get("assignments"));
        assertEquals(List.of("employments[0] import.continues_twice", "employments[1] import.continues_mismatch",
                "employments[2] import.employment_unknown", "employments[3] employment.continues_unknown",
                "assignments[0] import.continues_mismatch", "assignments[1] import.assignment_unknown"),
                errors(importPeople("2026-04-01", List.of(continuing("E3", "E1", e1.id()),
                        continuing("E2", null, "other"), continuing("E4", "NOPE", null),
                        continuing("E5", null, "nope")),
                        List.of(continuingAssignment("A2", "E2", "A2"), continuingAssignment("A3", "E2", "NOPE")))));

        // Another person cannot be continued.
        importPeople("2020-04-01", List.of(employment("D1", "doctor", null, null, null)),
                List.of(assignment("DA", "D1", "W", true, null)));
        String doctor = stored(ExternalId.Kind.EMPLOYMENT, "import", "D1");
        AssignmentRow unknown = new AssignmentRow(null, "A4", "E2", "W", null, null, null, null, false, null, null,
                null, "2026-04-01", null, null, null, null, null, "nope", null, null);
        AssignmentRow ofDoctor = new AssignmentRow(null, "A5", "E2", "W", null, null, null, null, false, null, null,
                null, "2026-04-01", null, null, null, null, null, stored(ExternalId.Kind.ASSIGNMENT, "import", "DA"),
                null, null);
        assertEquals(List.of("employments[0] employment.continues_other", "assignments[0] assignment.continues_unknown",
                "assignments[1] assignment.continues_other"), errors(importPeople("2026-04-01",
                List.of(continuing("E6", null, doctor)), List.of(unknown, ofDoctor))));
    }

    private void importTwoUnits() {
        importUnits("2026-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"));
    }

    private List<String> assignmentsOf(String employmentExternalId) {
        String id = stored(ExternalId.Kind.EMPLOYMENT, "import", employmentExternalId);
        return assignmentStore.rowsOfEmployment(id).stream()
                .map(r -> externalIdStore.ofResource(ExternalId.Kind.ASSIGNMENT, r.assignmentId()).getFirst()
                        .externalId() + " " + r.period().from() + " " + r.period().to() + " " + r.primary())
                .sorted()
                .toList();
    }

    @Test
    void importsStaffInOneDocumentAndIsIdempotent() {
        refItems.insert(new RefItem("t", RefKind.EMPLOYMENT_TYPE, "part-time", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("o", RefKind.OCCUPATION, "nurse", ItemNames.of("x"), 0, Map.of(), true));
        ImportDocument document = new ImportDocument("2026-04-01", false, "hr", null,
                List.of(unit("H", "Hospital", null), unit("W", "Ward", "H")),
                List.of(new EmploymentRow(null, "E1", "nurse", null, "N-1", "part-time", null, null,
                                new BigDecimal("50"), Map.of("k", "v"), "2020-04-01", null, null),
                        new EmploymentRow(null, "E2", null, "user-doctor", null, null, null, null, null, null, null,
                                null, null)),
                List.of(new AssignmentRow(null, "A1", "E1", "W", null, "nurse", null, true, true,
                                new BigDecimal("100"), 2, Map.of("a", 1), "2026-04-01", null),
                        new AssignmentRow(null, "A2", "E2", "H", null, null, null, null, null, null, null, null,
                                null, null)));

        ImportReport first = service.apply(document);
        ImportReport second = service.apply(document);

        assertEquals(List.of(), errors(first));
        assertEquals(new Counts(2, 0, 0), first.counts().get("employments"));
        assertEquals(new Counts(2, 0, 0), first.counts().get("assignments"));
        assertEquals(List.of(), errors(second));
        assertEquals(new Counts(0, 0, 2), second.counts().get("employments"));
        assertEquals(new Counts(0, 0, 2), second.counts().get("assignments"));
        assertEquals(List.of(), second.writes());
        var nurse = employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "hr", "E1")).orElseThrow();
        assertEquals("user-nurse", nurse.userId());
        assertEquals("N-1", nurse.staffNumber());
        assertEquals(LocalDate.of(2020, 4, 1), nurse.period().from());
        assertEquals(List.of("FUHITO_ORG_UNIT", "FUHITO_ORG_UNIT", "FUHITO_PERSON", "FUHITO_EMPLOYMENT",
                "FUHITO_PERSON", "FUHITO_EMPLOYMENT", "FUHITO_ASSIGNMENT", "FUHITO_ASSIGNMENT"),
                first.writes().stream().map(ImportReport.Write::resourceType).toList());
        assertTrue(first.writes().get(2) instanceof ImportReport.Write.OfPerson);
        assertEquals("fuhito/people/" + nurse.personId(), first.writes().get(2).path());
        assertTrue(first.writes().get(2).created());
        assertTrue(first.writes().get(3) instanceof ImportReport.Write.OfEmployment);
        assertTrue(first.writes().get(6) instanceof ImportReport.Write.OfAssignment);
    }

    @Test
    void aSecondDocumentTransfersOnePersonAndEndsAnother() {
        importTwoUnits();
        refItems.insert(new RefItem("r", RefKind.END_REASON, "resignation", ItemNames.of("x"), 0, Map.of(), true));
        importPeople("2026-04-01",
                List.of(employment("E1", "nurse", null, null, null), employment("E2", "doctor", null, null, null)),
                List.of(assignment("A1", "E1", "W", true, null), assignment("A2", "E2", "H", true, null)));

        // The new primary assignment comes first in the list. The ending one is still applied before it.
        ImportReport report = importPeople("2027-04-01",
                List.of(employment("E1", "nurse", "ON_LEAVE", null, null),
                        employment("E2", "doctor", null, "2027-04-01", "resignation")),
                List.of(assignment("A3", "E1", "H", true, null), assignment("A1", "E1", "W", true, "2027-04-01")));

        assertEquals(List.of(), errors(report));
        assertEquals(new Counts(0, 2, 0), report.counts().get("employments"));
        assertEquals(new Counts(1, 1, 0), report.counts().get("assignments"));
        assertEquals(List.of("A1 2026-04-01 2027-04-01 true", "A3 2027-04-01 null true"), assignmentsOf("E1"));
        assertEquals(List.of("A2 2026-04-01 2027-04-01 true"), assignmentsOf("E2"));
        var doctor = employments.get(stored(ExternalId.Kind.EMPLOYMENT, "import", "E2"));
        assertEquals("resignation", doctor.endReason());
        var nurse = employments.get(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1"));
        assertEquals(List.of("ACTIVE", "ON_LEAVE"),
                nurse.terms().stream().map(t -> t.terms().status().name()).toList());
        assertEquals(List.of("/terms", "/end", "/end", ""), report.writes().stream()
                .map(w -> w.path().substring(w.path().indexOf('/', 7) + 37)).toList());

        // The same second document again changes nothing, the ended rows included.
        ImportReport again = importPeople("2027-04-01",
                List.of(employment("E1", "nurse", "ON_LEAVE", null, null),
                        employment("E2", "doctor", null, "2027-04-01", "resignation")),
                List.of(assignment("A3", "E1", "H", true, null), assignment("A1", "E1", "W", true, "2027-04-01")));
        assertEquals(List.of(), errors(again));
        assertEquals(List.of(), again.writes());
    }

    @Test
    void aChangeOfAssignmentDetailsTakesEffectOnTheDate() {
        importTwoUnits();
        refItems.insert(new RefItem("j", RefKind.JOB_TITLE, "chief", ItemNames.of("x"), 0, Map.of(), true));
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null)));

        ImportReport report = importPeople("2027-04-01", List.of(),
                List.of(new AssignmentRow(null, "A1", "E1", "W", "chief", null, null, true, false, null, null, null,
                        null, null)));

        assertEquals(new Counts(0, 1, 0), report.counts().get("assignments"));
        String id = stored(ExternalId.Kind.ASSIGNMENT, "import", "A1");
        assertEquals(List.of("null", "chief"),
                assignments.get(id).rows().stream().map(r -> String.valueOf(r.jobTitle())).toList());
    }

    @Test
    void reportsEveryBadPeopleRow() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null)));
        List<EmploymentRow> employmentRows = new ArrayList<>();
        employmentRows.add(employment("E1", "doctor", null, null, null));
        employmentRows.add(employment("E9", "nobody", null, null, null));
        employmentRows.add(employment("E8", null, null, null, null));
        employmentRows.add(employment(null, "nurse", null, null, null));
        employmentRows.add(null);
        employmentRows.add(employment("E7", "doctor", "GONE", null, null));
        employmentRows.add(employment("E7", "doctor", null, null, null));
        List<AssignmentRow> assignmentRows = new ArrayList<>();
        assignmentRows.add(assignment("A1", "E1", "H", false, null));
        assignmentRows.add(assignment("A9", "NOPE", "W", false, null));
        assignmentRows.add(assignment("A8", "E1", "NOPE", false, null));
        assignmentRows.add(assignment("A7", null, "W", false, null));
        assignmentRows.add(assignment("A6", "E1", null, false, null));
        assignmentRows.add(assignment(null, "E1", "W", false, null));
        assignmentRows.add(null);
        assignmentRows.add(assignment("A5", "E1", "W", false, null));
        assignmentRows.add(assignment("A5", "E1", "W", false, null));

        ImportReport report = importPeople("2026-04-01", employmentRows, assignmentRows);

        assertEquals(List.of(
                "employments[0] import.user_mismatch",
                "employments[1] import.username_unknown",
                "employments[2] invalid.required",
                "employments[3] invalid.required",
                "employments[4] invalid.required",
                "employments[5] invalid.choice",
                "employments[6] import.duplicate_row",
                "assignments[0] import.assignment_mismatch",
                "assignments[1] import.employment_unknown",
                "assignments[2] import.org_unit_unknown",
                "assignments[3] invalid.required",
                "assignments[4] invalid.required",
                "assignments[5] invalid.required",
                "assignments[6] invalid.required",
                "assignments[8] import.duplicate_row"), errors(report));
        assertEquals(new Counts(1, 0, 0), report.counts().get("assignments"));
    }

    @Test
    void anExistingRowMustExistOnTheEffectiveDate() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null)));

        ImportReport report = importPeople("2026-03-01", List.of(employment("E1", "nurse", "ON_LEAVE", null, null)),
                List.of(assignment("A1", "E1", "W", true, null)));

        assertEquals(List.of("employments[0] employment.not_valid_on", "assignments[0] assignment.not_valid_on"),
                errors(report));
    }

    @Test
    void rejectsABadDocument() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.apply(null)).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.apply(doc(null, false, null, null, null))).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> service.apply(doc("April", false, null, null, null))).code());
        assertEquals("invalid.code", assertThrows(FuhitoException.class,
                () -> service.apply(doc("2026-04-01", false, "bad source", null, null))).code());
        ImportReport empty = service.apply(doc("2026-04-01", true, null, null, null));
        assertEquals(Map.of(), empty.counts());
        assertNull(empty.counts().get("orgUnits"));
        assertEquals(Map.of("refItems", new Counts(0, 0, 0), "orgUnits", new Counts(0, 0, 0)),
                service.apply(doc("2026-04-01", false, null, List.of(), List.of())).counts());
    }

    @Test
    void aNewEmploymentWithAnEndAndAReasonIsCreatedAndEnded() {
        refItems.insert(new RefItem("r", RefKind.END_REASON, "resignation", ItemNames.of("x"), 0, Map.of(), true));
        ImportDocument document = new ImportDocument("2026-04-01", false, "hr", null, null,
                List.of(new EmploymentRow(null, "E9", "nurse", null, null, null, null, null, null, null,
                        "2020-04-01", "2026-04-01", "resignation")), null);

        ImportReport report = service.apply(document);

        assertEquals(List.of(), errors(report));
        assertEquals(new Counts(1, 0, 0), report.counts().get("employments"));
        assertEquals(List.of("FUHITO_PERSON", "FUHITO_EMPLOYMENT", "FUHITO_EMPLOYMENT/end"), report.writes().stream()
                .map(w -> w.resourceType() + (w.path().endsWith("/end") ? "/end" : "")).toList());
        var stored = employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "hr", "E9")).orElseThrow();
        assertEquals(LocalDate.of(2026, 4, 1), stored.period().to());
        assertEquals("r", stored.endReasonId());
        assertEquals(new Counts(0, 0, 1), service.apply(document).counts().get("employments"));
    }

    private static Counts changed(ImportReport report, String list) {
        assertEquals(List.of(), errors(report));
        return report.counts().get(list);
    }

    // Each step changes one more field. So every field is the one that differs in one step.
    @Test
    void eachDetailOfAnOrgUnitAloneMakesTheRowAChange() {
        refItems.insert(new RefItem("w", RefKind.UNIT_TYPE, "ward", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("l", RefKind.WORK_LOCATION, "tokyo", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("c", RefKind.COST_CENTER, "cc", ItemNames.of("x"), 0, Map.of(), true));
        Map<String, OrgUnitName> shortName = Map.of("ja", new OrgUnitName("Unit", "U1", null));
        Map<String, OrgUnitName> english = Map.of("ja", new OrgUnitName("Unit", "U1", null),
                "en", new OrgUnitName("U-1", null, null));
        Map<String, OrgUnitName> names = Map.of("ja", new OrgUnitName("Unit", "U1", "ゆにっと"),
                "en", new OrgUnitName("U-1", null, null));
        List<OrgUnitRow> steps = List.of(
                new OrgUnitRow(null, "U", null, "ja", ja("Unit"), null, null, null, null, null, null, null, null),
                new OrgUnitRow(null, "U", null, "ja", shortName, null, null, null, null, null, null, null, null),
                new OrgUnitRow(null, "U", null, "ja", english, null, null, null, null, null, null, null, null),
                new OrgUnitRow(null, "U", null, "ja", names, null, null, null, null, null, null, null, null),
                new OrgUnitRow(null, "U", null, "en", names, null, null, null, null, null, null, null, null),
                new OrgUnitRow(null, "U", null, "en", names, "ward", null, null, null, null, null, null, null),
                new OrgUnitRow(null, "U", null, "en", names, "ward", null, null, 7, null, null, null, null),
                new OrgUnitRow(null, "U", null, "en", names, "ward", null, null, 7, Map.of("beds", 40), null, null,
                        null),
                new OrgUnitRow(null, "U", null, "en", names, "ward", null, null, 7, Map.of("beds", 40), null, null,
                        null, null, null, null, null, null, "tokyo", null),
                new OrgUnitRow(null, "U", null, "en", names, "ward", null, null, 7, Map.of("beds", 40), null, null,
                        null, null, null, null, null, null, "tokyo", "cc"));

        assertEquals(new Counts(1, 0, 0), changed(importUnits("2026-04-01", steps.getFirst()), "orgUnits"));
        for (OrgUnitRow step : steps.subList(1, steps.size())) {
            assertEquals(new Counts(0, 1, 0), changed(importUnits("2026-04-01", step), "orgUnits"), step.toString());
            assertEquals(new Counts(0, 0, 1), changed(importUnits("2026-04-01", step), "orgUnits"), step.toString());
        }
    }

    @Test
    void eachTermOfAnEmploymentAloneMakesTheRowAChange() {
        refItems.insert(new RefItem("t", RefKind.EMPLOYMENT_TYPE, "part-time", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("g", RefKind.GRADE, "g2", ItemNames.of("x"), 0, Map.of(), true));
        BigDecimal half = new BigDecimal("50");
        List<EmploymentRow> steps = List.of(
                new EmploymentRow(null, "E", "nurse", null, null, null, null, null, null, null, "2020-04-01", null,
                        null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", null, null, null, null, null, null,
                        null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", null, null, null, null, null,
                        null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE", null, null, null,
                        null, null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE", half, null, null,
                        null, null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE",
                        new BigDecimal("60"), null, null, null, null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE",
                        new BigDecimal("60"), Map.of("shift", "night"), null, null, null),
                // A row with no work percent removes the stored one.
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE", null,
                        Map.of("shift", "night"), null, null, null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE", null, null,
                        "2027-03-31", Map.of("shift", "night"), null, null, null, null, null, null, null, null, null,
                        null),
                new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE", null, null,
                        "2028-03-31", Map.of("shift", "night"), null, null, null, null, null, null, null, null, null,
                        null));

        assertEquals(new Counts(1, 0, 0),
                changed(importPeople("2026-04-01", List.of(steps.getFirst()), null), "employments"));
        for (EmploymentRow step : steps.subList(1, steps.size())) {
            assertEquals(new Counts(0, 1, 0),
                    changed(importPeople("2026-04-01", List.of(step), null), "employments"), step.toString());
            assertEquals(new Counts(0, 0, 1),
                    changed(importPeople("2026-04-01", List.of(step), null), "employments"), step.toString());
        }
    }

    @Test
    void eachDetailOfAnAssignmentAloneMakesTheRowAChange() {
        importTwoUnits();
        refItems.insert(new RefItem("j", RefKind.JOB_TITLE, "director", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("o", RefKind.OCCUPATION, "nurse", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("k", RefKind.ASSIGNMENT_KIND, "support", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("l", RefKind.WORK_LOCATION, "home", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("c", RefKind.COST_CENTER, "cc", ItemNames.of("x"), 0, Map.of(), true));
        importPeople("2026-04-01", List.of(employment("E", "nurse", null, null, null)), null);
        BigDecimal all = new BigDecimal("100");
        List<AssignmentRow> steps = List.of(
                new AssignmentRow(null, "A", "E", "W", null, null, null, null, null, null, null, null, null, null),
                new AssignmentRow(null, "A", "E", "W", "director", null, null, null, null, null, null, null, null,
                        null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", null, null, null, null, null, null, null,
                        null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", null, null, null, null, null,
                        null, null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, null, null, null, null,
                        null, null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, true, null, null, null,
                        null, null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, true, all, null, null,
                        null, null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, true, all, 4, null,
                        null, null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, true, all, 4,
                        Map.of("room", "3"), null, null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, true, all, 4,
                        Map.of("room", "3"), null, null, null, null, null, null, null, "home", null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, true, all, 4,
                        Map.of("room", "3"), null, null, null, null, null, null, null, "home", "cc"));

        assertEquals(new Counts(1, 0, 0),
                changed(importPeople("2026-04-01", null, List.of(steps.getFirst())), "assignments"));
        for (AssignmentRow step : steps.subList(1, steps.size())) {
            assertEquals(new Counts(0, 1, 0),
                    changed(importPeople("2026-04-01", null, List.of(step)), "assignments"), step.toString());
            assertEquals(new Counts(0, 0, 1),
                    changed(importPeople("2026-04-01", null, List.of(step)), "assignments"), step.toString());
        }
    }

    @Test
    void aRowCanNameItsOwnSource() {
        ImportDocument document = new ImportDocument("2026-04-01", false, null, null,
                List.of(new OrgUnitRow("hr-2", "U", null, "ja", ja("Unit"), null, null, null, null, null,
                        null, null, null)),
                List.of(new EmploymentRow("hr-2", "E", "nurse", null, null, null, null, null, null, null, null, null,
                                null),
                        new EmploymentRow("hr-2", "E", "nurse", null, null, null, null, null, null, null, null, null,
                                null)),
                List.of(new AssignmentRow("hr-2", "A", "E", "U", null, null, null, null, null, null, null, null,
                        null, null)));

        ImportReport report = service.apply(document);

        // The second employment row repeats the first one of the same source.
        assertEquals(List.of("employments[1] import.duplicate_row"), errors(report));
        assertTrue(externalIdStore.idOf(ExternalId.Kind.EMPLOYMENT, "hr-2", "E").isPresent());
        assertTrue(externalIdStore.idOf(ExternalId.Kind.ASSIGNMENT, "hr-2", "A").isPresent());
        assertTrue(externalIdStore.idOf(ExternalId.Kind.EMPLOYMENT, "import", "E").isEmpty());
    }

    @Test
    void aRowOfAnotherSystemFindsTheRecordByTheIdThatSystemHasForIt() {
        // The HR system makes the unit, the person, and the employment.
        ImportReport fromHr = service.apply(new ImportDocument("2026-04-01", false, "hr", null,
                List.of(unit("H", "Hospital", null)),
                List.of(new EmploymentRow(null, "E1", null, null, null, null, null, null, null, null, null,
                        "2020-04-01", null, null, null, "P1", null, null, null)),
                null, List.of(new PersonRow(null, "P1", null, null, "ja", japanese("Hanako"), null, null))));
        assertEquals(List.of(), errors(fromHr));
        String unit = stored(ExternalId.Kind.ORG_UNIT, "hr", "H");
        String person = stored(ExternalId.Kind.PERSON, "hr", "P1");
        String employment = stored(ExternalId.Kind.EMPLOYMENT, "hr", "E1");
        // The payroll system has its own IDs for them.
        assertEquals(List.of(), errors(service.apply(ImportDocument.of("2026-04-01", false, "payroll", "externalIds",
                List.of(new ExternalIdRow("ORG_UNIT", unit, null, "PAY-H"),
                        new ExternalIdRow("PERSON", person, null, "PAY-1"),
                        new ExternalIdRow("EMPLOYMENT", employment, null, "PAY-E1"))))));

        ImportReport fromPayroll = service.apply(new ImportDocument("2026-04-01", false, "payroll", null, null,
                List.of(new EmploymentRow(null, "PAY-E1", null, null, null, null, null, null, null, null, null,
                        "2020-04-01", null, null, null, "PAY-1", null, null, null)),
                List.of(new AssignmentRow(null, "PAY-A1", "PAY-E1", "PAY-H", null, null, null, null, true, null,
                        null, null, null, null)),
                List.of(new PersonRow(null, "PAY-1", null, null, "ja", japanese("Hanako"), null, null),
                        new PersonRow(null, "PAY-2", null, null, "ja", japanese("Taro"), null, null))));

        assertEquals(List.of(), errors(fromPayroll));
        assertEquals(new Counts(1, 0, 1), fromPayroll.counts().get("people"));
        assertEquals(new Counts(0, 0, 1), fromPayroll.counts().get("employments"));
        assertEquals(new Counts(1, 0, 0), fromPayroll.counts().get("assignments"));
        // The records of HR keep their source. A new record has the system of its row as its source.
        assertEquals("hr", personStore.find(person).orElseThrow().source());
        assertEquals("payroll", personStore.find(stored(ExternalId.Kind.PERSON, "payroll", "PAY-2")).orElseThrow()
                .source());
        String assignment = stored(ExternalId.Kind.ASSIGNMENT, "payroll", "PAY-A1");
        assertEquals(employment, assignmentStore.rows(assignment).getFirst().employmentId());
        assertEquals(unit, assignmentStore.rows(assignment).getFirst().orgUnitId());
        assertEquals("payroll", assignmentStore.rows(assignment).getFirst().source());
        // A row with the ID of the record and an ID of a system names the record only when the system has that ID.
        assertEquals(List.of("people[0] import.id_mismatch"), errors(service.apply(new ImportDocument("2026-04-01",
                false, "payroll", null, null, null, null,
                List.of(new PersonRow(null, "PAY-9", null, null, "ja", japanese("Hanako"), null, person))))));
        assertEquals(new Counts(0, 0, 1), service.apply(new ImportDocument("2026-04-01", false, "payroll", null,
                null, null, null, List.of(new PersonRow(null, "PAY-1", null, null, "ja", japanese("Hanako"), null,
                        person)))).counts().get("people"));
    }

    @Test
    void theEndOfAnEmploymentIsComparedByDateAndByReason() {
        refItems.insert(new RefItem("r1", RefKind.END_REASON, "resignation", ItemNames.of("x"), 0, Map.of(), true));
        refItems.insert(new RefItem("r2", RefKind.END_REASON, "retirement", ItemNames.of("x"), 0, Map.of(), true));
        EmploymentRow open = new EmploymentRow(null, "E", "nurse", null, null, null, null, null, null, null,
                "2020-04-01", "2027-04-01", null);

        // A new row with an end date and no reason is created with that end. Nothing ends it a second time.
        ImportReport created = importPeople("2026-04-01", List.of(open), null);
        assertEquals(List.of("FUHITO_PERSON", "FUHITO_EMPLOYMENT"),
                created.writes().stream().map(ImportReport.Write::resourceType).toList());
        // The employment still runs on the effective date, so its terms are compared.
        assertEquals(new Counts(0, 0, 1), changed(importPeople("2026-04-01", List.of(open), null), "employments"));

        EmploymentRow resigned = employment("E", "nurse", null, "2027-04-01", "resignation");
        assertEquals(new Counts(0, 1, 0),
                changed(importPeople("2026-04-01", List.of(resigned), null), "employments"));
        assertEquals(new Counts(0, 0, 1),
                changed(importPeople("2026-04-01", List.of(resigned), null), "employments"));
        EmploymentRow retired = employment("E", "nurse", null, "2027-04-01", "retirement");
        assertEquals(new Counts(0, 1, 0), changed(importPeople("2026-04-01", List.of(retired), null), "employments"));
        // After the end, there are no terms to compare.
        assertEquals(new Counts(0, 0, 1), changed(importPeople("2028-04-01", List.of(retired), null), "employments"));
    }

    @Test
    void anAssignmentCannotGoToAnotherOrgUnit() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E", "nurse", null, null, null)),
                List.of(assignment("A", "E", "W", true, null)));

        ImportReport report = importPeople("2026-04-01", null, List.of(assignment("A", "E", "H", true, null)));

        assertEquals(List.of("assignments[0] import.assignment_mismatch"), errors(report));
    }

    @Test
    void aReferenceItemIsComparedByItsAttributesAndByWhetherItIsActive() {
        RefItemRow base = new RefItemRow("UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null, Map.of("k", "v"), true);
        service.apply(doc("2026-04-01", false, null, List.of(base), null));

        assertEquals(new Counts(0, 0, 1),
                service.apply(doc("2026-04-01", false, null, List.of(base), null)).counts().get("refItems"));
        assertEquals(new Counts(0, 1, 0), service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null, Map.of("k", "w"), true)), null))
                .counts().get("refItems"));
        assertEquals(new Counts(0, 1, 0), service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null, Map.of("k", "w"), false)), null))
                .counts().get("refItems"));
    }

    @Test
    void anAssignmentCannotGoToAnotherEmployment() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null),
                        employment("E2", "doctor", null, null, null)),
                List.of(assignment("A", "E1", "W", true, null)));

        ImportReport report = importPeople("2026-04-01", null, List.of(assignment("A", "E2", "W", true, null)));

        assertEquals(List.of("assignments[0] import.assignment_mismatch"), errors(report));
    }
}
