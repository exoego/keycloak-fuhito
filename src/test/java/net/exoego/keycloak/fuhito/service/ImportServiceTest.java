package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ResourceType;
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
import net.exoego.keycloak.fuhito.store.LongWrite;
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
    private final MemoryPlaceStore places = new MemoryPlaceStore(orgUnitStore, assignmentStore);
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, externalIdStore, assignmentStore, refItems,
            Today.of(Clock.systemUTC()), AttributeRules.NONE, places);
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
            new MemoryReportingLineStore(), new MemoryUnitRoleStore(), new AnyEmployer(refItems));
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, externalIdStore, employmentStore, orgUnitStore, refItems,
            new MemoryReportingLineStore());
    private final MemoryAttributeDefinitionStore definitionStore = new MemoryAttributeDefinitionStore();
    private final RealmKindService kinds = new RealmKindService(new MemoryRealmKindStore(), refItems, definitionStore);
    private final MemoryAttributeValueStore values = new MemoryAttributeValueStore();
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
            new AttributeDefinitionService(definitionStore, values, refItems, kinds::isKnown);
    // Whether the import runs, and how many of its rows are done.
    private boolean running;
    private int rowsDone;
    private final ImportService service = new ImportService(new RefItemService(refItems), refItems, orgUnits,
            orgUnitStore, new ImportService.People(employments, employmentStore, assignments, assignmentStore, users,
                    new PersonService(personStore, externalIdStore, users, id -> false)),
            new ImportService.Setup(kinds, definitions, hierarchies),
            new ImportService.Organizations(new OrganizationLinkService(orgUnitStore, organizations), organizations,
                    managed::add),
            new ImportService.Records(lines, roles, qualifications, new ExternalIdService(externalIdStore),
                    new ChangeOrderService(orderStore, null, users, Clock.systemUTC())), new LongWrite() {
                @Override
                public <T> T run(Supplier<T> work) {
                    running = true;
                    try {
                        return work.get();
                    } finally {
                        running = false;
                    }
                }

                @Override
                public void rowDone() {
                    assertTrue(running);
                    rowsDone++;
                }
            });

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
                new RefItemRow("hospital", "UNIT_TYPE", "hospital", "ja",
                        Map.of("ja", new RefItemName("病院"), "en", new RefItemName("Hospital")), 1,
                        Map.of("k", "v"), null),
                new RefItemRow("nurse", "OCCUPATION", "nurse", "ja", ItemNames.japanese("看護師"), null, null,
                        false));

        ImportReport first = service.apply(doc("2026-04-01", false, null, rows, null));
        ImportReport second = service.apply(doc("2026-04-01", false, null, rows, null));

        assertEquals(Map.of("refItems", new Counts(2, 0, 0)), first.counts());
        assertEquals(Map.of("refItems", new Counts(0, 0, 2)), second.counts());
        assertEquals(List.of(), first.errors());
        assertEquals(2, first.writes().size());
        assertTrue(first.writes().getFirst().created());
        assertEquals(ResourceType.FUHITO_REF_ITEM, first.writes().getFirst().resourceType());
        assertEquals("fuhito/ref-items/" + first.writes().getFirst().resourceId(), first.writes().getFirst().path());
        assertEquals(List.of(), second.writes());
        RefItem hospital = refItems.findByCode(RefKind.UNIT_TYPE, "hospital").orElseThrow();
        assertEquals(new Localized<>("ja", Map.of("ja", new RefItemName("病院"), "en", new RefItemName("Hospital"))),
                hospital.names());
        assertEquals(new ImportReport.Write.OfRefItem(true, hospital), first.writes().getFirst());
        assertEquals(hospital.id(), stored(ExternalId.Kind.REF_ITEM, "import", "hospital"));
        assertFalse(refItems.findByCode(RefKind.OCCUPATION, "nurse").orElseThrow().active());
    }

    @Test
    void importsTheKindsOfTheRealmAndRenamesAStoredOne() {
        List<RefKindRow> rows = List.of(new RefKindRow(null, null, "SITE", "site", "ja",
                Map.of("ja", new RefKindName("拠点"), "en", new RefKindName("Site"))));

        ImportReport first = service.apply(kinds(rows));
        ImportReport second = service.apply(kinds(rows));
        ImportReport renamed = service.apply(kinds(List.of(
                new RefKindRow(null, null, "SITE", null, "ja", Map.of("ja", new RefKindName("勤務地"))),
                new RefKindRow(null, null, "UT", "UNIT_TYPE", "ja", Map.of("ja", new RefKindName("組織種別"))))));

        assertEquals(Map.of("refKinds", new Counts(1, 0, 0)), first.counts());
        assertEquals("fuhito/ref-kinds/site", first.writes().getFirst().path());
        assertEquals(Map.of("refKinds", new Counts(0, 0, 1)), second.counts());
        assertEquals(Map.of("refKinds", new Counts(0, 1, 0)), renamed.counts());
        // A fixed kind is in every realm, so a row cannot add it.
        assertEquals(List.of("refKinds[1] ref_kind.code_taken"), errors(renamed));
        // The names merge by language, so the English name stays.
        assertEquals(new Localized<>("ja", Map.of("ja", new RefKindName("勤務地"), "en", new RefKindName("Site"))),
                kinds.get("site").names());
        assertEquals(kinds.get("site").id(), stored(ExternalId.Kind.REF_KIND, "import", "SITE"));
    }

    @Test
    void aRowWithTwoTagsOfOneLanguageFailsAndKeepsTheStoredNames() {
        Localized<RefKindName> stored = new Localized<>("ja", Map.of("ja", new RefKindName("拠点"),
                "en", new RefKindName("Site")));
        service.apply(kinds(List.of(new RefKindRow(null, null, "SITE", "site", "ja", stored.byLanguage()))));
        Map<String, RefKindName> removing = new LinkedHashMap<>();
        removing.put("ja", new RefKindName("勤務地"));
        removing.put("JA", null);
        Map<String, RefKindName> twice = new LinkedHashMap<>();
        twice.put("en-us", new RefKindName("Room"));
        twice.put("en-US", new RefKindName("Rooms"));

        ImportReport report = service.apply(kinds(List.of(new RefKindRow(null, null, "SITE", null, "ja", removing),
                new RefKindRow(null, null, "ROOM", "room", "en", twice))));

        assertEquals(List.of("refKinds[0] invalid.duplicate_language", "refKinds[1] invalid.duplicate_language"),
                errors(report));
        assertEquals(Map.of("field", "names.JA", "value", "ja"), report.errors().getFirst().error().params());
        assertEquals(stored, kinds.get("site").names());
        assertEquals(Optional.empty(), kinds.find("room"));
    }

    @Test
    void aRowNamesAStoredKindByItsIdOrExternalIdAndNeverByItsCode() {
        Map<String, RefKindName> names = Map.of("ja", new RefKindName("拠点"));
        service.apply(kinds(List.of(new RefKindRow(null, null, "SITE", "site", "ja", names),
                new RefKindRow(null, null, "AREA", "area", "ja", names))));
        String site = kinds.get("site").id();
        String area = kinds.get("area").id();

        ImportReport report = service.apply(kinds(List.of(
                new RefKindRow(null, null, null, "room", "ja", names),
                new RefKindRow(null, "hr", "S-1", "site", "ja", names),
                new RefKindRow(site, null, null, "place", "ja", names),
                new RefKindRow(area, null, "SITE", null, "ja", names),
                new RefKindRow("nope", null, null, "room", "ja", names),
                new RefKindRow(null, null, "SITE", null, "ja", names),
                new RefKindRow(null, null, "SITE", null, "ja", names))));

        assertEquals(List.of("refKinds[0] invalid.required", "refKinds[1] ref_kind.code_taken",
                "refKinds[2] import.value_fixed", "refKinds[3] import.id_mismatch", "refKinds[4] import.id_unknown",
                "refKinds[6] import.duplicate_row"), errors(report));
        assertEquals("code", report.errors().get(2).error().params().get("field"));
        assertEquals(Map.of("refKinds", new Counts(0, 0, 1)), report.counts());
        // An ID of another system names the kind once the kind has it.
        externalIdStore.insert(new ExternalId("row-s", ExternalId.Kind.REF_KIND, site, "hr", "S-1", 1));
        assertEquals(Map.of("refKinds", new Counts(0, 0, 1)), service.apply(kinds(List.of(
                new RefKindRow(null, "hr", "S-1", null, "ja", names)))).counts());
    }

    private static ImportDocument kinds(List<RefKindRow> rows) {
        return ImportDocument.of("2026-04-01", false, null, "refKinds", rows);
    }

    @Test
    void importsTheDefinitionsOfAttributesAndChangesTheRuleOfAStoredOne() {
        List<AttributeDefinitionRow> rows = List.of(
                new AttributeDefinitionRow(null, null, "NOTE", "PERSON", "note", "STRING", null, null, null),
                new AttributeDefinitionRow(null, null, "TYPE", "ORG_UNIT", "type", "REF_ITEM", "UNIT_TYPE", null,
                        true));

        ImportReport first = service.apply(definitions(rows));
        ImportReport second = service.apply(definitions(rows));
        String type = stored(ExternalId.Kind.ATTRIBUTE_DEFINITION, "import", "TYPE");
        ImportReport changed = service.apply(definitions(List.of(
                new AttributeDefinitionRow(null, null, "NOTE", null, null, "STRING", null, null, true),
                new AttributeDefinitionRow(null, null, "BAD", "PERSON", "bad", "STRING", "UNIT_TYPE", null, null),
                new AttributeDefinitionRow(null, null, "NOTE-2", "PERSON", "note", "STRING", null, null, null),
                new AttributeDefinitionRow(type, null, null, "PERSON", "type", "REF_ITEM", "UNIT_TYPE", null, true),
                new AttributeDefinitionRow(null, null, null, "PERSON", "memo", "STRING", null, null, null))));

        assertEquals(Map.of("attributeDefinitions", new Counts(2, 0, 0)), first.counts());
        assertEquals("fuhito/attribute-definitions/" + first.writes().getFirst().resourceId(),
                first.writes().getFirst().path());
        assertEquals(Map.of("attributeDefinitions", new Counts(0, 0, 2)), second.counts());
        assertEquals(Map.of("attributeDefinitions", new Counts(0, 1, 0)), changed.counts());
        assertEquals(List.of("attributeDefinitions[1] attribute.ref_kind",
                "attributeDefinitions[2] attribute.name_taken", "attributeDefinitions[3] import.value_fixed",
                "attributeDefinitions[4] invalid.required"),
                errors(changed));
        assertEquals("target", changed.errors().get(2).error().params().get("field"));
        assertTrue(definitions.list("PERSON").getFirst().required());
    }

    private static ImportDocument definitions(List<AttributeDefinitionRow> rows) {
        return ImportDocument.of("2026-04-01", false, null, "attributeDefinitions", rows);
    }

    @Test
    void importsTheHierarchiesAndRenamesTheDefaultOneByItsId() {
        String defaultId = hierarchies.find("default").orElseThrow().id();
        List<HierarchyRow> rows = List.of(new HierarchyRow(null, null, "P", "project", "プロジェクト"),
                new HierarchyRow(defaultId, null, null, null, "組織図"));

        ImportReport first = service.apply(hierarchies(rows));
        ImportReport second = service.apply(hierarchies(rows));
        ImportReport bad = service.apply(hierarchies(List.of(new HierarchyRow(null, null, "D", "default", "組織図"),
                new HierarchyRow(defaultId, null, null, "project", "組織図"),
                new HierarchyRow(null, null, null, "finance", "管理会計"))));

        assertEquals(Map.of("hierarchies", new Counts(1, 1, 0)), first.counts());
        assertEquals(Map.of("hierarchies", new Counts(0, 0, 2)), second.counts());
        assertEquals("組織図", hierarchies.find("default").orElseThrow().name());
        assertEquals("fuhito/hierarchies/" + hierarchies.find("project").orElseThrow().id(),
                first.writes().getFirst().path());
        assertEquals(hierarchies.find("project").orElseThrow().id(), stored(ExternalId.Kind.HIERARCHY, "import", "P"));
        // The code "default" is a value too, so it does not name the default hierarchy.
        assertEquals(List.of("hierarchies[0] hierarchy.code_taken", "hierarchies[1] import.value_fixed",
                "hierarchies[2] invalid.required"), errors(bad));
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
    void tellsTheStorageWhenEachRowIsDone() {
        List<OrgUnitRow> units = List.of(ending("OLD", "2026-04-01", List.of("NEW"), null),
                starting("NEW", "2026-04-01"), unit("BAD", "部", "NONE"));

        ImportReport report = service.apply(doc("2026-04-01", false, null, null, units));

        assertEquals(List.of("orgUnits[2] import.parent_unknown"), errors(report));
        // One for each row, one for the new units, and one for the end of the unit with successors.
        assertEquals(5, rowsDone);
        assertFalse(running);
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
        ImportReport same = importUnits("2026-04-01", timed("H", "Asia/Tokyo", null));
        ImportReport cleared = importUnits("2026-04-01", ImportRows.clearing(timed("H", null, null), "dayStart"));

        String h = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        assertEquals(List.of(), errors(first));
        assertEquals("fuhito/org-units/" + h + "/time-settings", first.writes().getLast().path());
        assertEquals(Map.of("orgUnits", new Counts(0, 0, 1)), again.counts());
        assertEquals(Map.of("orgUnits", new Counts(0, 0, 1)), same.counts());
        assertEquals(Map.of("orgUnits", new Counts(0, 1, 0)), cleared.counts());
        assertEquals("Asia/Tokyo", orgUnitStore.findUnit(h).orElseThrow().timeZone());
        assertEquals(null, orgUnitStore.findUnit(h).orElseThrow().dayStart());
        assertEquals(1, errors(importUnits("2026-04-01", timed("H", "Nowhere/City", null))).size());
        assertEquals(List.of("orgUnits[0] invalid.blank"), errors(importUnits("2026-04-01", timed("H", "", null))));
    }

    @Test
    void linksAUnitToAnOrganizationByItsAliasAndAsksToManageIt() {
        ImportReport first = importUnits("2026-04-01", linked("H", "x-hospital"), linked("K", null));
        ImportReport again = importUnits("2026-04-01", linked("H", "x-hospital"), linked("K", null));
        ImportReport moved = importUnits("2026-04-01",
                ImportRows.clearing(linked("H", null), "organizationAlias"), linked("K", "x-hospital"),
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

    private static OrgUnitRow inHierarchy(OrgUnitRow row, String hierarchy) {
        return new OrgUnitRow(row.source(), row.externalId(), row.code(), row.primaryLanguage(), row.names(),
                row.unitType(), row.parentExternalId(), row.parentId(), row.displayOrder(), row.attributes(),
                row.validFrom(), row.validTo(), row.id(), row.successorExternalIds(), row.successorIds(),
                row.organizationAlias(), row.timeZone(), row.dayStart(), row.workLocation(), row.costCenter(),
                hierarchy, row.nulls());
    }

    @Test
    void aRowOfAnOrgUnitTakesOnlyTheDefaultHierarchy() {
        orgUnitStore.hierarchyCodes.put("region-id", "region");
        importUnits("2026-04-01", unit("P", "P", null), unit("K", "K", null), unit("C", "C", "P"));
        String p = stored(ExternalId.Kind.ORG_UNIT, "import", "P");
        String c = stored(ExternalId.Kind.ORG_UNIT, "import", "C");

        // The list orgUnitParents gives a parent in another hierarchy, for a stored unit and a new one alike.
        ImportReport bad = importUnits("2026-04-01", inHierarchy(unit("C", "C", "K"), "region"),
                inHierarchy(unit("N", "N", null), "region"), inHierarchy(unit("P", "P", null), "nope"));
        assertEquals(List.of("orgUnits[0] invalid.value_fixed", "orgUnits[1] invalid.value_fixed",
                "orgUnits[2] invalid.value_fixed"), errors(bad));
        assertEquals(Map.of("field", "hierarchy"), bad.errors().getFirst().error().params());
        ImportReport report = importUnits("2026-04-01", inHierarchy(unit("C", "C", "P"), "default"),
                inHierarchy(unit("O", "O", null), "default"));

        assertEquals(List.of(), errors(report));
        assertEquals(Map.of("orgUnits", new Counts(1, 0, 1)), report.counts());
        assertEquals(p, orgUnits.get(c, "2026-04-01").parentId());
        assertNull(orgUnits.get(c, "2026-04-01", "region").parentId());
    }

    private static ImportDocument hierarchies(List<HierarchyRow> rows) {
        return ImportDocument.of("2026-04-01", false, null, "hierarchies", rows);
    }

    @Test
    void importsTheNamesOfAReferenceItemFromTheirDates() {
        List<RefItemRow> rows = List.of(
                new RefItemRow(null, null, "M1", "JOB_TITLE", "m1", "en", ItemNames.english("Manager"), null, null,
                        null, null, null, null, null, "2020-04-01"),
                new RefItemRow(null, null, "M1", "JOB_TITLE", "m1", "en", ItemNames.english("Senior Manager"), null,
                        null, null, null, null, null, null, "2023-04-01"));

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
                List.of(new RefItemRow("M1", null, null, "en", ItemNames.english("Head"), null, null, null)), null));

        assertEquals(List.of("Manager", "Senior Manager", "Head"),
                refItems.names(item.id()).stream().map(n -> n.names().primary().name()).toList());
        assertEquals(LocalDate.of(2026, 4, 1), refItems.names(item.id()).getLast().validFrom());
        assertEquals(List.of("refItems[0] invalid.date"), errors(service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow(null, null, "M1", "JOB_TITLE", "m1", "en", ItemNames.english("Head"), null, null,
                        null, null, null, null, null, "soon")),
                null))));
        // Two rows of the same names of an item would hide one of them.
        assertEquals(List.of("refItems[1] import.duplicate_row"), errors(service.apply(doc("2026-04-01", false, null,
                List.of(rows.getFirst(), rows.getFirst()), null))));
    }

    @Test
    void changesAReferenceItemThatDiffers() {
        service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("H", "UNIT_TYPE", "hospital", "ja", ItemNames.japanese("病院"), null, null,
                        null)), null));

        ImportReport report = service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("H", "UNIT_TYPE", "hospital", "ja", ItemNames.japanese("総合病院"), 3, null,
                        false)), null));

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
        rows.add(new RefItemRow("OK", "UNIT_TYPE", "ok", "en", ItemNames.english("OK"), null, null, null));
        rows.add(new RefItemRow("X1", "NOPE", "x", "en", ItemNames.english("x"), null, null, null));
        rows.add(null);
        rows.add(new RefItemRow("X2", null, "x", "en", ItemNames.english("x"), null, null, null));
        rows.add(new RefItemRow("X3", "UNIT_TYPE", "bad code", "en", ItemNames.english("x"), null, null, null));
        rows.add(new RefItemRow("X4", "UNIT_TYPE", "blank", "en", ItemNames.english(" "), null, null, null));

        ImportReport report = service.apply(doc("2026-04-01", false, null, rows, null));

        assertEquals(List.of("refItems[1] invalid.kind", "refItems[2] invalid.required",
                "refItems[3] invalid.required", "refItems[4] invalid.code", "refItems[5] invalid.blank"),
                errors(report));
        assertEquals(Map.of("refItems", new Counts(1, 0, 0)), report.counts());
    }

    @Test
    void aRowNamesAStoredReferenceItemByItsIdOrExternalIdAndNeverByItsCode() {
        service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("W", "UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null, null, null)),
                null));
        String ward = stored(ExternalId.Kind.REF_ITEM, "import", "W");

        ImportReport report = service.apply(doc("2026-04-01", false, null, List.of(
                new RefItemRow(null, "UNIT_TYPE", "room", "en", ItemNames.english("Room"), null, null, null),
                new RefItemRow("W2", "UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null, null, null),
                new RefItemRow(ward, null, null, null, null, "en", ItemNames.english("Ward"), null, null, null, null,
                        null, null, null, null),
                new RefItemRow("W", "OCCUPATION", "ward", "en", ItemNames.english("Ward"), null, null, null),
                new RefItemRow("nope", null, null, "UNIT_TYPE", "x", "en", ItemNames.english("x"), null, null, null,
                        null, null, null, null, null)), null));

        assertEquals(List.of("refItems[0] invalid.required", "refItems[1] ref_item.code_taken",
                "refItems[3] import.value_fixed", "refItems[4] import.id_unknown"), errors(report));
        assertEquals("kind", report.errors().get(2).error().params().get("field"));
        assertEquals(Map.of("refItems", new Counts(0, 0, 1)), report.counts());
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
            new OrgUnitRow("hr", "H", "hq", "EN", Map.of("en", new OrgUnitName("Hospital", "H", null),
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

    private static OrgUnitRow typed(String externalId, String name, String unitType, String parentExternalId) {
        return new OrgUnitRow(null, externalId, null, "ja", ja(name), unitType, parentExternalId, null, null, null,
                null, null, null);
    }

    @Test
    void importsTheMarkOfRollupUnitsAndKeepsTheirUnitsOutOfTheDefaultHierarchy() {
        orgUnitStore.refItems = refItems;
        ImportReport items = service.apply(doc("2026-04-01", false, null, List.of(
                new RefItemRow(null, null, "R", "UNIT_TYPE", "region", "en", ItemNames.english("Region"), null, null,
                        null, null, null, null, true, null),
                new RefItemRow(null, null, "X", "UNIT_TYPE", "x", "en", ItemNames.english("X"), null, null, null,
                        null, null, true, true, null),
                new RefItemRow(null, null, "Y", "UNIT_TYPE", "y", "en", ItemNames.english("Y"), null, null, null,
                        true, null, null, true, null)), null));
        assertEquals(List.of("refItems[1] ref_item.rollup_lateral", "refItems[2] ref_item.rollup_legal_entity"),
                errors(items));
        assertTrue(refItems.findByCode(RefKind.UNIT_TYPE, "region").orElseThrow().rollup());
        // A row that leaves the mark out keeps it.
        assertEquals(List.of(), errors(service.apply(doc("2026-04-01", false, null, List.of(new RefItemRow("R",
                "UNIT_TYPE", "region", "en", ItemNames.english("Region"), 1, null, null)), null))));
        assertTrue(refItems.findByCode(RefKind.UNIT_TYPE, "region").orElseThrow().rollup());

        ImportReport units = importUnits("2026-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"),
                typed("K", "Kanto", "region", null), typed("B", "Bad", "region", "H"), unit("C", "Child", "K"));

        assertEquals(List.of("orgUnits[3] org_unit.rollup_has_parent", "orgUnits[4] org_unit.rollup_has_child"),
                errors(units));
        assertEquals(List.of("Hospital", "Ward < Hospital"), tree("2026-04-01"));
        String ward = stored(ExternalId.Kind.ORG_UNIT, "import", "W");
        // A row that makes a unit a rollup unit and clears the parent takes the parent away first.
        assertEquals(List.of("orgUnits[0] org_unit.rollup_has_parent"),
                errors(importUnits("2026-06-01", typed("W", "Ward", "region", null))));
        assertEquals(List.of(), errors(importUnits("2026-06-01",
                ImportRows.clearing(typed("W", "Ward", "region", null), "parentExternalId"))));
        assertTrue(orgUnits.get(ward, "2026-06-01").rollup());
        assertEquals(List.of("Hospital"), tree("2026-06-01"));
        assertEquals(List.of("orgUnits[0] org_unit.rollup_has_parent"),
                errors(importUnits("2026-07-01", typed("W", "Ward", "region", "H"))));
        // A row that clears the rollup type can give the unit a parent at once.
        assertEquals(List.of(), errors(importUnits("2026-08-01",
                ImportRows.clearing(unit("W", "Ward", "H"), "unitType"))));
        assertEquals(List.of("Hospital", "Ward < Hospital"), tree("2026-08-01"));
        assertEquals(List.of("Hospital"), tree("2026-07-31"));
    }

    private static OrgUnitRow coded(String externalId, String code, String parentExternalId) {
        return new OrgUnitRow(null, externalId, code, "ja", ja(externalId), null, parentExternalId, null, null, null,
                null, null, null);
    }

    // The reads of one unit, of the units of a list, of the work locations and cost centers that apply, and of one
    // ID of another system.
    private List<Integer> reads(Supplier<ImportReport> run) {
        int one = orgUnitStore.oneReads;
        int many = orgUnitStore.manyReads;
        int defaults = orgUnitStore.defaultsReads;
        int single = externalIdStore.singleFinds;
        assertEquals(List.of(), errors(run.get()));
        return List.of(orgUnitStore.oneReads - one, orgUnitStore.manyReads - many,
                orgUnitStore.defaultsReads - defaults, externalIdStore.singleFinds - single);
    }

    @Test
    void readsWhatTheRowsOfAListOfUnitsNameWithOneReadForEachKind() {
        OrgUnitRow[] rows = {coded("H", "h", null), coded("N", "n", "H"), coded("W", "w", "N")};

        // The new units read the codes of the list, and then what applies to them on their first date.
        assertEquals(List.of(0, 1, 1, 0), reads(() -> importUnits("2026-04-01", rows)));
        // The same units again read the units, their versions, their parents, and the codes.
        assertEquals(List.of(0, 4, 0, 0), reads(() -> importUnits("2026-04-01", rows)));
        assertEquals(List.of("H", "N < H", "W < N"), tree("2026-04-01"));
        assertEquals(new Counts(0, 0, 3), importUnits("2026-04-01", rows).counts().get("orgUnits"));
    }

    @Test
    void aRowOfUnitsSeesTheCodesOfTheRowsBeforeIt() {
        importUnits("2026-04-01", coded("S", "s", null), coded("R", "r", null));

        ImportReport report = importUnits("2027-04-01", coded("A", "a", null), coded("B", "a", null),
                coded("S", "t", null), coded("D", "s", null), coded("E", "t", null), coded("F", "u", null),
                coded("R", "u", null));

        // A new unit cannot take the code of a new one before it or the code that a stored one took before it. It
        // can take the code that a stored one gave up before it. A stored unit cannot take the code of a new one
        // before it.
        assertEquals(List.of("orgUnits[1] org_unit.code_taken", "orgUnits[4] org_unit.code_taken",
                "orgUnits[6] org_unit.code_taken"), errors(report));
        assertEquals(new Counts(3, 1, 0), report.counts().get("orgUnits"));
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
                "orgUnits[5] invalid.blank",
                "orgUnits[9] invalid.code",
                "orgUnits[4] import.parent_unknown",
                "orgUnits[6] import.parent_failed",
                "orgUnits[8] import.parent_cycle",
                "orgUnits[7] import.parent_failed",
                "orgUnits[10] org_unit.unit_type_unknown",
                "orgUnits[11] import.parent_twice",
                "orgUnits[12] import.parent_id_unknown"), errors(report));
        assertEquals(new Counts(1, 0, 0), report.counts().get("orgUnits"));
        assertEquals(Map.of("field", "names.ja.name"), report.errors().get(3).error().params());
        assertEquals(Map.of("field", "source"), report.errors().get(4).error().params());
        assertEquals(Map.of("parentExternalId", "NOPE"), report.errors().get(5).error().params());
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
        String nurse = personStore.findByUser("user-nurse").orElseThrow().id();
        List<PersonRow> rows = List.of(
                new PersonRow(null, "P1", null, null, "ja", Map.of("ja", new PersonName("山田 花子", "やまだ はなこ"),
                        "en", new PersonName("Hanako Yamada", null)), Map.of("note", "retired"), null),
                new PersonRow(null, null, "nurse", null, null, null, null, nurse));

        ImportReport first = service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null, rows));

        assertEquals(new Counts(1, 0, 1), first.counts().get("people"));
        Person retired = personStore.find(stored(ExternalId.Kind.PERSON, "import", "P1")).orElseThrow();
        assertEquals(null, retired.userId());
        assertEquals("やまだ はなこ", retired.names().primary().phoneticName());
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

    private ImportReport importPersons(PersonRow... rows) {
        return service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null, List.of(rows)));
    }

    private static PersonRow ofUser(String externalId, String username, String id) {
        return new PersonRow(null, externalId, username, null, null, null, null, id);
    }

    @Test
    void aNewPersonCannotTakeAUserWhoHasAPerson() {
        assertEquals(List.of(), errors(importPersons(ofUser("P1", "nurse", null))));
        String nurse = stored(ExternalId.Kind.PERSON, "import", "P1");

        ImportReport taken = importPersons(ofUser("P2", "nurse", null));
        assertEquals(List.of("people[0] import.user_taken"), errors(taken));
        assertEquals(Map.of("userId", "user-nurse", "personId", nurse), taken.errors().getFirst().error().params());
        assertTrue(externalIdStore.idOf(ExternalId.Kind.PERSON, "import", "P2").isEmpty());

        // The file names the person of the user by its ID or by an external ID that it has.
        assertEquals(new Counts(0, 0, 1), importPersons(ofUser(null, "nurse", nurse)).counts().get("people"));
        assertEquals(new Counts(0, 0, 1), importPersons(ofUser("P1", "nurse", null)).counts().get("people"));
        // A row of a stored person that names another user links the person to that user.
        assertEquals(new Counts(0, 1, 0), importPersons(ofUser("P1", "doctor", null)).counts().get("people"));
        assertEquals("user-doctor", personStore.find(nurse).orElseThrow().userId());
        assertEquals(new Counts(1, 0, 0), importPersons(ofUser("P3", "nurse", null)).counts().get("people"));
        assertEquals(List.of("people[0] person.user_taken"), errors(importPersons(ofUser(null, "nurse", nurse))));
    }

    @Test
    void aRowThatNamesTheUserByIdAndByUsernameFails() {
        importTwoUnits();

        ImportReport person = importPersons(new PersonRow(null, "P1", "nurse", "user-doctor", null, null, null, null));
        assertEquals(List.of("people[0] import.user_twice"), errors(person));
        assertEquals(Map.of("field", "userId"), person.errors().getFirst().error().params());
        assertEquals(List.of("employments[0] import.user_twice"), errors(importEmployments(new EmploymentRow(null,
                "E1", "nurse", "user-doctor", null, null, null, null, null, null, null, "2020-04-01", null, null, null,
                null, null, null, null))));
        assertTrue(personStore.findByUser("user-nurse").isEmpty());
        assertTrue(personStore.findByUser("user-doctor").isEmpty());
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
        assertEquals(Employer.legalEntity(hospital), stored.employer());

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

    private static EmploymentRow atAgency(String externalId, String personId, String agency, String validFrom,
            String employerId) {
        return new EmploymentRow(null, externalId, null, null, "1001", null, null, null, null, null, null, null,
                validFrom, null, null, null, null, personId, employerId, null, null, null, agency);
    }

    @Test
    void aRowNamesAnExternalEmployerByItsCodeAndCannotChangeIt() {
        importTwoUnits();
        String hospital = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        refItems.insert(new RefItem("agency", RefKind.EXTERNAL_EMPLOYER, "staffing", ItemNames.of("Staffing"), 0,
                Map.of(), true));
        refItems.insert(new RefItem("other", RefKind.EXTERNAL_EMPLOYER, "contracting", ItemNames.of("Contracting"),
                0, Map.of(), true));
        String taro = new PersonService(personStore, externalIdStore, users, id -> false)
                .create(new PersonValues(null, "ja", japanese("Taro"), null, null, null)).person().id();

        assertEquals(List.of(), errors(importEmployments(atAgency("E1", taro, "staffing", "2020-04-01", null))));
        Employment stored = employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1")).orElseThrow();
        assertEquals(Employer.external("agency"), stored.employer());
        assertEquals("1001", stored.staffNumber());

        // The same row changes nothing, also when the agency takes no new employments.
        refItems.update(new RefItem("agency", RefKind.EXTERNAL_EMPLOYER, "staffing", ItemNames.of("Staffing"), 0,
                Map.of(), false));
        assertEquals(new Counts(0, 0, 1), importEmployments(atAgency("E1", taro, "staffing", null, null)).counts()
                .get("employments"));
        assertEquals(List.of("employments[0] import.employer_mismatch",
                "employments[1] employment.external_employer_inactive", "employments[2] employment.employer_twice",
                "employments[3] employment.external_employer_unknown"),
                errors(importEmployments(atAgency("E1", taro, "contracting", null, null),
                        atAgency("E3", taro, "staffing", "2027-04-01", null),
                        atAgency("E4", taro, "contracting", "2027-04-01", hospital),
                        atAgency("E5", taro, "nope", "2027-04-01", null))));
        assertEquals(List.of("employments[0] import.employer_mismatch"),
                errors(importEmployments(byIds(null, null, hospital, null, stored.id()))));
        assertEquals(1, employmentStore.employments.size());
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

    private static ReportingLineRow line(String id, String externalId, String assignment, String managerExternalId,
            String managerUsername, String validTo) {
        return new ReportingLineRow(id, null, externalId, assignment, null, managerExternalId, null, managerUsername,
                null, null, validTo);
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

        ImportReport first = importLines("2026-04-01", line(null, "L1", "A1", null, "doctor", null));
        ImportReport again = importLines("2026-04-01", line(null, "L1", "A1", null, "doctor", null));
        ImportReport second = importLines("2026-05-01", line(null, "L2", "A1", "P3", null, null));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("reportingLines", new Counts(1, 0, 0)), first.counts());
        assertEquals(Map.of("reportingLines", new Counts(0, 0, 1)), again.counts());
        String doctor = lineStore.lines.getFirst().id();
        assertEquals(List.of("reportingLines[0] reporting_line.solid_exists"), errors(second));
        assertEquals(doctor, second.errors().getFirst().error().params().get("lineId"));
        assertEquals(List.of(), second.writes());

        // The row that ends the stored line can come after the new line, and the same file loads again.
        ReportingLineRow[] change = {line(null, "L2", "A1", "P3", null, null),
            line(null, "L1", null, null, null, "2026-05-01")};
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
        assertEquals(chiefLine, stored(ExternalId.Kind.REPORTING_LINE, "import", "L2"));
        assertEquals(stored(ExternalId.Kind.PERSON, "import", "P3"), stored.getLast().managerPersonId());
        assertEquals("fuhito/reporting-lines/" + doctor + "/end", next.writes().getFirst().path());

        ImportReport back = importLines("2026-06-01", line(chiefLine, null, null, null, null, "2026-06-01"),
                line(null, "L3", "A1", null, "doctor", null));
        assertEquals(List.of(), errors(back));
        assertEquals(Map.of("reportingLines", new Counts(1, 1, 0)), back.counts());
        assertEquals(3, lineStore.lines.size());
        assertEquals(List.of("reportingLines[0] import.assignment_unknown", "reportingLines[1] import.manager_twice",
                "reportingLines[2] import.username_unknown", "reportingLines[3] import.id_unknown",
                "reportingLines[4] import.person_unknown", "reportingLines[5] invalid.required",
                "reportingLines[6] invalid.required"),
                errors(importLines("2026-05-01", line(null, "X0", "NOPE", "P3", null, null),
                        line(null, "X1", "A1", "P3", "doctor", null), line(null, "X2", "A1", null, "nobody", null),
                        line("nope", null, null, null, null, null), line(null, "X4", "A1", "NOPE", null, null),
                        line(null, "X5", "A1", null, null, null), line(null, null, "A1", "P3", null, null))));
        String a1 = stored(ExternalId.Kind.ASSIGNMENT, "import", "A1");
        // The assignment, the manager, and the kind of a stored line do not change. A different line has a
        // different external ID.
        ImportReport fixed = importLines("2026-05-01",
                new ReportingLineRow(null, null, "X7", "A1", a1, "P3", null, null, null, null, null),
                line(chiefLine, "L1", null, null, null, null),
                line(null, "L2", null, null, "doctor", null),
                line(null, "L2", null, null, null, null),
                new ReportingLineRow(doctor, null, null, null, null, null, null, null, "DOTTED", null, null));
        assertEquals(List.of("reportingLines[0] import.assignment_twice", "reportingLines[1] import.id_mismatch",
                "reportingLines[2] import.value_fixed", "reportingLines[3] import.duplicate_row",
                "reportingLines[4] import.value_fixed"), errors(fixed));
        assertEquals("managerPersonId", fixed.errors().get(2).error().params().get("field"));
        assertEquals("kind", fixed.errors().get(4).error().params().get("field"));
    }

    private static UnitRoleRow role(String id, String externalId, String employment, String orgUnit, String role,
            Boolean below, String validTo) {
        return new UnitRoleRow(id, null, externalId, employment, null, orgUnit, null, role, below, null, validTo);
    }

    private ImportReport importRoles(String effectiveDate, UnitRoleRow... rows) {
        return service.apply(ImportDocument.of(effectiveDate, false, null, "unitRoles", List.of(rows)));
    }

    @Test
    void importsRolesAndKeepsWhetherARoleCoversTheUnitsBelow() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)), null);
        refItems.insert(new RefItem("approver-id", RefKind.UNIT_ROLE.name(), "approver", ItemNames.of("承認者"), 0,
                Map.of(), true, false, false, false, false, 1, null, null));

        ImportReport first = importRoles("2026-04-01", role(null, "R1", "E1", "H", "approver", null, null));
        ImportReport again = importRoles("2026-04-01", role(null, "R1", "E1", "H", "approver", false, null));
        ImportReport wider = importRoles("2026-05-01", role(null, "R2", "E1", "H", "approver", true, null));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("unitRoles", new Counts(1, 0, 0)), first.counts());
        assertEquals(Map.of("unitRoles", new Counts(0, 0, 1)), again.counts());
        String narrow = roleStore.roles.getFirst().id();
        assertEquals(List.of("unitRoles[0] unit_role.overlap"), errors(wider));
        assertEquals(narrow, wider.errors().getFirst().error().params().get("id"));
        assertEquals(List.of(), wider.writes());
        assertEquals(List.of("unitRoles[0] import.value_fixed", "unitRoles[1] import.value_fixed"),
                errors(importRoles("2026-05-01", role(narrow, null, null, null, null, true, null),
                        role(null, "R1", "E1", "H", "approver", true, null))));

        // The row that ends the stored role can come after the new role, and the same file loads again.
        UnitRoleRow[] change = {role(null, "R2", "E1", "H", "approver", true, null),
            role(null, "R1", null, null, null, null, "2026-05-01")};
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
        assertEquals(wide, stored(ExternalId.Kind.UNIT_ROLE, "import", "R2"));
        assertEquals("fuhito/unit-roles/" + narrow + "/end", changed.writes().getFirst().path());

        ImportReport back = importRoles("2026-06-01", role(wide, null, null, null, null, null, "2026-06-01"),
                role(null, "R3", "E1", "H", "approver", false, null));
        assertEquals(List.of(), errors(back));
        assertEquals(Map.of("unitRoles", new Counts(1, 1, 0)), back.counts());
        assertEquals(3, roleStore.roles.size());
        ImportReport bad = importRoles("2026-05-01", role(null, "X0", "NOPE", "H", "approver", null, null),
                role(null, "X1", "E1", "NOPE", "approver", null, null),
                role("nope", null, null, null, null, null, null),
                role(null, "X3", "E1", "W", null, null, null),
                role(wide, null, null, "W", null, true, null),
                role(null, "R2", null, null, "other", null, null),
                role(null, "R2", null, null, null, null, null),
                role(null, null, "E1", "H", "approver", true, null));
        assertEquals(List.of("unitRoles[0] import.employment_unknown", "unitRoles[1] import.org_unit_unknown",
                "unitRoles[2] import.id_unknown", "unitRoles[3] invalid.required",
                "unitRoles[4] import.value_fixed", "unitRoles[5] import.value_fixed",
                "unitRoles[6] import.duplicate_row", "unitRoles[7] invalid.required"), errors(bad));
        assertEquals("orgUnitId", bad.errors().get(4).error().params().get("field"));
        assertEquals("role", bad.errors().get(5).error().params().get("field"));
        String e1 = stored(ExternalId.Kind.EMPLOYMENT, "import", "E1");
        String h = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        assertEquals(List.of("unitRoles[0] import.employment_twice", "unitRoles[1] import.org_unit_twice"),
                errors(importRoles("2026-05-01",
                        new UnitRoleRow(null, null, "X8", "E1", e1, null, h, "approver", true, null, null),
                        new UnitRoleRow(null, null, "X9", null, e1, "H", h, "approver", true, null, null))));
    }

    private ImportReport unitAndStaff(String effectiveDate, String name, BigDecimal workPercent, Integer displayOrder,
            String validFrom) {
        return service.apply(new ImportDocument(effectiveDate, false, null, null,
                List.of(new OrgUnitRow(null, "H", null, "ja", ja(name), null, null, null, null, null, validFrom,
                        null, null)),
                List.of(new EmploymentRow(null, "E1", "nurse", null, null, null, null, null, null, workPercent, null,
                        validFrom, null, null, null, null, null, null, null)),
                List.of(new AssignmentRow(null, "A1", "E1", "H", null, null, null, null, true, null, displayOrder,
                        null, validFrom, null))));
    }

    private static ReportingLineRow lineFrom(String validFrom) {
        return new ReportingLineRow(null, null, "L1", "A1", null, null, null, "doctor", null, validFrom, null);
    }

    private static UnitRoleRow roleFrom(String validFrom) {
        return new UnitRoleRow(null, null, "R1", "E1", null, "H", null, "approver", null, validFrom, null);
    }

    @Test
    void aRowOfAStoredThingLeavesOutItsStartOrGivesTheStoredOne() {
        refItems.insert(new RefItem("approver-id", RefKind.UNIT_ROLE.name(), "approver", ItemNames.of("承認者"), 0,
                Map.of(), true, false, false, false, false, 1, null, null));
        assertEquals(List.of(), errors(unitAndStaff("2026-04-01", "Hospital", null, null, "2020-04-01")));
        assertEquals(List.of(), errors(importLines("2026-04-01", lineFrom("2021-04-01"))));
        assertEquals(List.of(), errors(importRoles("2026-04-01", roleFrom("2021-04-01"))));
        // The details of the unit and of the assignment and the terms of the employment of a later date start later
        // than the things themselves.
        ImportReport changed = unitAndStaff("2026-05-01", "General Hospital", BigDecimal.valueOf(80), 2, null);
        assertEquals(List.of(), errors(changed));
        assertEquals(Map.of("orgUnits", new Counts(0, 1, 0), "employments", new Counts(0, 1, 0),
                "assignments", new Counts(0, 1, 0)), changed.counts());

        ImportReport same = unitAndStaff("2026-06-01", "General Hospital", BigDecimal.valueOf(80), 2, "2020-04-01");
        ImportReport other = unitAndStaff("2026-06-01", "General Hospital", BigDecimal.valueOf(80), 2, "2026-05-01");

        assertEquals(List.of(), errors(same));
        assertEquals(List.of(), same.writes());
        assertEquals(List.of("orgUnits[0] import.value_fixed", "employments[0] import.value_fixed",
                "assignments[0] import.value_fixed"), errors(other));
        assertEquals(List.of(Map.of("id", stored(ExternalId.Kind.ORG_UNIT, "import", "H"), "field", "validFrom"),
                Map.of("id", stored(ExternalId.Kind.EMPLOYMENT, "import", "E1"), "field", "validFrom"),
                Map.of("id", stored(ExternalId.Kind.ASSIGNMENT, "import", "A1"), "field", "validFrom")),
                other.errors().stream().map(e -> e.error().params()).toList());
        assertEquals(new Counts(0, 0, 1), importLines("2026-06-01", lineFrom("2021-04-01")).counts()
                .get("reportingLines"));
        assertEquals(List.of("reportingLines[0] import.value_fixed"),
                errors(importLines("2026-06-01", lineFrom("2026-04-01"))));
        assertEquals(new Counts(0, 0, 1), importRoles("2026-06-01", roleFrom("2021-04-01")).counts()
                .get("unitRoles"));
        assertEquals(List.of("unitRoles[0] import.value_fixed"),
                errors(importRoles("2026-06-01", roleFrom("2026-04-01"))));
        assertEquals(List.of("unitRoles[0] invalid.date"), errors(importRoles("2026-06-01", roleFrom("2021/04/01"))));
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
                Map.of(), true, false, false, false, false, 1, null, null));
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

    @Test
    void aQualificationRowWithNoStartKeepsTheStoredStart() {
        refItems.insert(new RefItem("license-id", RefKind.QUALIFICATION.name(), "license", ItemNames.of("免許"), 0,
                Map.of(), true, false, false, false, false, 1, null, null));
        service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null,
                List.of(new PersonRow(null, "P1", null, null, "ja", japanese("Ann"), null, null))));
        importQualifications(qualification(null, "Q1", "P1", null, "123", null));
        String q1 = stored(ExternalId.Kind.QUALIFICATION, "import", "Q1");

        QualificationRow same = new QualificationRow(null, null, "Q1", null, null, null, "license", "123", null, null,
                null);
        QualificationRow renewed = new QualificationRow(q1, null, null, null, null, null, "license", "456", null,
                "2030-04-01", null);
        ImportReport unchanged = importQualifications(same);
        ImportReport changed = importQualifications(renewed);
        ImportReport again = importQualifications(renewed);
        ImportReport added = importQualifications(new QualificationRow(null, null, "Q2", "P1", null, null, "license",
                null, null, null, null));

        assertEquals(Map.of("qualifications", new Counts(0, 0, 1)), unchanged.counts());
        assertEquals(List.of(), unchanged.writes());
        assertEquals(Map.of("qualifications", new Counts(0, 1, 0)), changed.counts());
        assertEquals(Map.of("qualifications", new Counts(0, 0, 1)), again.counts());
        assertEquals(List.of(), again.writes());
        Qualification renewal = qualificationStore.find(q1).orElseThrow();
        assertEquals("2020-04-01", renewal.period().from().toString());
        assertEquals("456", renewal.number());
        assertEquals("2030-04-01", renewal.period().to().toString());
        assertEquals(Map.of("qualifications", new Counts(1, 0, 0)), added.counts());
        assertEquals("2026-04-01", qualificationStore.find(stored(ExternalId.Kind.QUALIFICATION, "import", "Q2"))
                .orElseThrow().period().from().toString());
    }

    private ImportReport importExternalIds(ExternalIdRow... rows) {
        return service.apply(ImportDocument.of("2026-04-01", false, null, "externalIds", List.of(rows)));
    }

    // Each write of IDs with its resource, its path, and the IDs that the resource has after it.
    private static List<String> idWrites(ImportReport report) {
        return report.writes().stream()
                .map(write -> (write.deleted() ? "DELETE " : write.created() ? "CREATE " : "UPDATE ")
                        + write.resourceType() + " " + write.resourceId() + " " + write.path() + " "
                        + ((ImportReport.Write.OfExternalIds) write).state().stream()
                                .map(id -> id.source() + "=" + id.externalId()).toList())
                .toList();
    }

    @Test
    void importsTheIdsOfOtherSystemsAndRemovesOneWithNull() {
        externalIdStore.resource(ExternalId.Kind.ORG_UNIT, "u-1", "hr");
        externalIdStore.insert(new ExternalId("row-h", ExternalId.Kind.ORG_UNIT, "u-1", "hr", "H", 1));

        ImportReport first = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-1"));
        ImportReport again = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-1"));
        ImportReport changed = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-2"));
        ImportReport removed = importExternalIds(
                ImportRows.clearing(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", null), "externalId"),
                ImportRows.clearing(new ExternalIdRow("ORG_UNIT", "u-1", "ledger", null), "externalId"));

        assertEquals(Map.of("externalIds", new Counts(1, 0, 0)), first.counts());
        // A write of an ID is an update of its resource.
        assertEquals(List.of("UPDATE FUHITO_ORG_UNIT u-1 fuhito/org-units/u-1 [hr=H, payroll=P-1]"),
                idWrites(first));
        assertEquals(Map.of("externalIds", new Counts(0, 0, 1)), again.counts());
        assertEquals(List.of(), again.writes());
        assertEquals(Map.of("externalIds", new Counts(0, 1, 0)), changed.counts());
        assertEquals(List.of("UPDATE FUHITO_ORG_UNIT u-1 fuhito/org-units/u-1 [hr=H, payroll=P-2]"),
                idWrites(changed));
        assertEquals(Map.of("externalIds", new Counts(0, 1, 1)), removed.counts());
        assertEquals(List.of("UPDATE FUHITO_ORG_UNIT u-1 fuhito/org-units/u-1 [hr=H]"), idWrites(removed));
        assertEquals(List.of("H"), externalIdStore.rows.stream().map(ExternalId::externalId).toList());
        // A row of the system that made the resource replaces its ID too. A row that leaves out the ID keeps it.
        assertEquals(List.of("externalIds[1] import.duplicate_row", "externalIds[3] external_id.resource_unknown",
                "externalIds[5] invalid.blank"),
                errors(importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-3"),
                        new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-4"),
                        new ExternalIdRow("ORG_UNIT", "u-1", "hr", "X"),
                        new ExternalIdRow("ORG_UNIT", "nope", "payroll", "P-5"),
                        new ExternalIdRow("ORG_UNIT", "u-1", "ledger", null),
                        new ExternalIdRow("ORG_UNIT", "u-1", "audit", " "))));
    }

    @Test
    void aTextWithSpacesAroundItFailsItsRow() {
        externalIdStore.resource(ExternalId.Kind.ORG_UNIT, "u-1", "hr");

        ImportReport units = importUnits("2026-04-01", unit("A", "内科 ", null), unit("B\n", "外科", null),
                timed("C", "　Asia/Tokyo", null), timed("D", null, "05:00\t"));
        ImportReport orders = importOrders(order(null, "O-1", null, " April", null, null),
                new ChangeOrderRow(null, null, "O-2", null, null, "April", null, null, null, null, null, null, null,
                        "DOC-9 "));
        ImportReport ids = importExternalIds(new ExternalIdRow("ORG_UNIT", "u-1", "payroll", "P-1 "));

        // A bad key fails before the import reads stored data, so its row comes first.
        assertEquals(List.of("orgUnits[1] invalid.surrounding_space", "orgUnits[0] invalid.surrounding_space",
                "orgUnits[2] invalid.surrounding_space", "orgUnits[3] invalid.surrounding_space"), errors(units));
        assertEquals(List.of("invalid.surrounding_space externalId", "invalid.surrounding_space names.ja.name",
                "invalid.surrounding_space timeZone", "invalid.surrounding_space dayStart"), fields(units));
        assertEquals(List.of("invalid.surrounding_space title", "invalid.surrounding_space externalReference"),
                fields(orders));
        assertEquals(List.of("invalid.surrounding_space externalId"), fields(ids));
    }

    // A row names stored things by these keys. A key in a form that a write never stores would find nothing.
    @Test
    void aKeyWithSpacesAroundItFailsBeforeTheImportLooksItUp() {
        importUnits("2026-04-01", unit("H", "Hospital", null));
        String h = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        importOrders(order(null, "O-1", null, "April", null, null));
        String o = stored(ExternalId.Kind.CHANGE_ORDER, "import", "O-1");
        service.apply(ImportDocument.of("2026-04-01", false, null, "people",
                List.of(new PersonRow(null, "P1", null, null, "ja", japanese("山田"), null, null))));
        String p = stored(ExternalId.Kind.PERSON, "import", "P1");

        ImportReport units = importUnits("2026-05-01",
                new OrgUnitRow(null, "H ", null, "ja", ja("Hospital"), null, null, null, null, null, null, null, h),
                unit("W", "Ward", "\tH"),
                new OrgUnitRow(null, "K", null, "ja", ja("Clinic"), null, null, null, null, null, null, null, null,
                        List.of("H\n"), null, null, null, null, null, null),
                new OrgUnitRow("h r", "H", null, "ja", ja("Hospital"), null, null, null, null, null, null, null, h));
        ImportReport orders = importOrders(order(o, "O-1　", null, "April", null, null));
        ImportReport people = service.apply(ImportDocument.of("2026-05-01", false, null, "people",
                List.of(new PersonRow(null, " P1", null, null, "ja", japanese("山田"), null, p))));

        assertEquals(List.of("invalid.surrounding_space externalId", "invalid.surrounding_space parentExternalId",
                "invalid.surrounding_space successorExternalIds", "invalid.code source"), fields(units));
        assertEquals(List.of("invalid.surrounding_space externalId"), fields(orders));
        assertEquals(List.of("invalid.surrounding_space externalId"), fields(people));
        assertEquals("Hospital", orgUnits.get(h, "2026-05-01").version().names().primary().name());
    }

    @Test
    void aRowOfAnIdWritesAKindOfReferenceDataByItsCodeAndTheLastRemovalLeavesNoIds() {
        service.apply(kinds(List.of(new RefKindRow(null, null, "SITE", "site", "ja",
                Map.of("ja", new RefKindName("拠点"))))));
        String site = kinds.get("site").id();
        externalIdStore.resource(ExternalId.Kind.REF_KIND, site, null);

        ImportReport added = importExternalIds(new ExternalIdRow("REF_KIND", site, "hr", "S-1"));
        ImportReport removed = importExternalIds(
                ImportRows.clearing(new ExternalIdRow("REF_KIND", site, "hr", null), "externalId"),
                ImportRows.clearing(new ExternalIdRow("REF_KIND", site, "import", null), "externalId"));

        assertEquals(List.of("UPDATE FUHITO_REF_KIND site fuhito/ref-kinds/site [hr=S-1, import=SITE]"),
                idWrites(added));
        assertEquals(List.of("UPDATE FUHITO_REF_KIND site fuhito/ref-kinds/site [import=SITE]",
                "UPDATE FUHITO_REF_KIND site fuhito/ref-kinds/site []"), idWrites(removed));
    }

    private static ChangeOrderRow order(String id, String externalId, String number, String title,
            String approverUserId, String approverUsername) {
        return new ChangeOrderRow(id, null, externalId, number, null, title, null, null, null, approverUserId,
                approverUsername, null, null, null);
    }

    private ImportReport importOrders(ChangeOrderRow... rows) {
        return importOrders("2026-04-01", rows);
    }

    private ImportReport importOrders(String effectiveDate, ChangeOrderRow... rows) {
        return service.apply(ImportDocument.of(effectiveDate, false, null, "changeOrders", List.of(rows)));
    }

    @Test
    void keepsTheEffectiveDateOfAStoredChangeOrderWhenARowHasNone() {
        importOrders(order(null, "O1", "2026-01", "異動", null, null));
        String id = orderStore.findByNumber("2026-01").orElseThrow().id();

        ImportReport changed = importOrders("2026-05-01", order(null, "O1", "2026-01", "人事異動", null, null));
        ImportReport again = importOrders("2026-05-01", order(null, "O1", "2026-01", "人事異動", null, null));
        ImportReport byId = importOrders("2026-06-01", order(id, null, "2026-01", "人事異動", null, null));
        ImportReport created = importOrders("2026-05-01", order(null, "O2", "2026-02", "異動", null, null));

        assertEquals(List.of(), errors(changed));
        assertEquals(Map.of("changeOrders", new Counts(0, 1, 0)), changed.counts());
        assertEquals(Map.of("changeOrders", new Counts(0, 0, 1)), again.counts());
        assertEquals(List.of(), again.writes());
        assertEquals(Map.of("changeOrders", new Counts(0, 0, 1)), byId.counts());
        ChangeOrder stored = orderStore.findByNumber("2026-01").orElseThrow();
        assertEquals("人事異動", stored.title());
        assertEquals("2026-04-01", stored.effectiveDate().toString());
        assertEquals(Map.of("changeOrders", new Counts(1, 0, 0)), created.counts());
        assertEquals("2026-05-01", orderStore.findByNumber("2026-02").orElseThrow().effectiveDate().toString());
    }

    @Test
    void importsChangeOrdersByExternalIdAndReplacesAStoredOne() {
        ImportReport first = importOrders(order(null, "O1", "2026-01", "異動", null, "doctor"));
        ImportReport again = importOrders(order(null, "O1", "2026-01", "異動", null, "doctor"));
        ImportReport changed = importOrders(order(null, "O1", "2026-01", "人事異動", "user-doctor", null));

        assertEquals(List.of(), errors(first));
        assertEquals(Map.of("changeOrders", new Counts(1, 0, 0)), first.counts());
        assertEquals(Map.of("changeOrders", new Counts(0, 0, 1)), again.counts());
        assertEquals(Map.of("changeOrders", new Counts(0, 1, 0)), changed.counts());
        ChangeOrder stored = orderStore.findByNumber("2026-01").orElseThrow();
        assertEquals("人事異動", stored.title());
        assertEquals("user-doctor", stored.approverUserId());
        assertEquals("2026-04-01", stored.effectiveDate().toString());
        assertEquals("fuhito/change-orders/" + stored.id(), changed.writes().getFirst().path());
        assertEquals(stored.id(), stored(ExternalId.Kind.CHANGE_ORDER, "import", "O1"));
        // The number is a value. A new order with a taken number fails, and a stored order can take another one.
        assertEquals(List.of("changeOrders[0] invalid.required", "changeOrders[1] import.id_unknown",
                "changeOrders[2] import.user_twice", "changeOrders[3] import.username_unknown",
                "changeOrders[5] import.duplicate_row", "changeOrders[6] change_order.number_taken"),
                errors(importOrders(order(null, null, "2026-09", "X", null, null),
                        order("nope", null, null, "X", null, null),
                        order(null, "O2", "2026-02", "X", "user-doctor", "doctor"),
                        order(null, "O3", "2026-03", "X", null, "nobody"),
                        order(stored.id(), null, "2026-01", "人事異動", "user-doctor", null),
                        order(stored.id(), "O1", "2026-01", "人事異動", "user-doctor", null),
                        order(null, "O4", "2026-01", "X", null, null))));
        assertEquals(Map.of("changeOrders", new Counts(0, 1, 0)),
                importOrders(order(null, "O1", "2026-11", "人事異動", "user-doctor", null)).counts());
        assertEquals("2026-11", orderStore.find(stored.id()).orElseThrow().number());
    }

    private static EmploymentRow continuing(String externalId, String continuesExternalId, String continuesId) {
        return new EmploymentRow(null, externalId, "nurse", null, null, null, null, null, null, null, null,
                "2026-04-01", null, null, null, null, null, null, null, continuesExternalId, continuesId);
    }

    @Test
    void linksANewEmploymentToTheOneItContinues() {
        importUnits("2020-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"));
        assertEquals(List.of(), errors(importPeople("2020-04-01",
                List.of(employment("E1", "nurse", null, "2026-04-01", null)),
                List.of(assignment("A1", "E1", "W", true, null)))));

        ImportReport first = importPeople("2026-04-01", List.of(continuing("E2", "E1", null)),
                List.of(assignment("A2", "E2", "W", true, null)));
        ImportReport again = importPeople("2026-04-01", List.of(continuing("E2", "E1", null)),
                List.of(assignment("A2", "E2", "W", true, null)));

        assertEquals(List.of(), errors(first));
        Employment e1 = employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1")).orElseThrow();
        assertEquals(e1.id(), employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "import", "E2")).orElseThrow()
                .continuesEmploymentId());
        assertEquals(new Counts(0, 0, 1), again.counts().get("employments"));
        assertEquals(new Counts(0, 0, 1), again.counts().get("assignments"));
        assertEquals(List.of("employments[0] import.continues_twice", "employments[1] import.continues_mismatch",
                "employments[2] import.employment_unknown", "employments[3] employment.continues_unknown"),
                errors(importPeople("2026-04-01", List.of(continuing("E3", "E1", e1.id()),
                        continuing("E2", null, "other"), continuing("E4", "NOPE", null),
                        continuing("E5", null, "nope")), null)));

        // Another person cannot be continued.
        importPeople("2020-04-01", List.of(employment("D1", "doctor", null, null, null)),
                List.of(assignment("DA", "D1", "W", true, null)));
        String doctor = stored(ExternalId.Kind.EMPLOYMENT, "import", "D1");
        assertEquals(List.of("employments[0] employment.continues_other"), errors(importPeople("2026-04-01",
                List.of(continuing("E6", null, doctor)), null)));
    }

    private static EmploymentRow employmentAfter(String externalId, String username, String validFrom,
            String continuesExternalId) {
        return new EmploymentRow(null, externalId, username, null, null, null, null, null, null, null, null,
                validFrom, null, null, null, null, null, null, null, continuesExternalId, null);
    }

    private static AssignmentRow assignmentAfter(String externalId, String employment, String orgUnit,
            String validFrom) {
        return new AssignmentRow(null, externalId, employment, orgUnit, null, null, null, null, false, null, null,
                null, validFrom, null);
    }

    // The nurse and the doctor work from 2020. The nurse has assignments to the ward and to the hospital.
    private void staffOf2020() {
        importUnits("2020-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"));
        assertEquals(List.of(), errors(importPeople("2020-04-01",
                List.of(employment("E1", "nurse", null, null, null), employment("D1", "doctor", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null), assignment("C1", "E1", "H", false, null),
                        assignment("B1", "D1", "W", false, null)))));
    }

    @Test
    void aFileEndsTheEmploymentThatANewOneContinuesInAnyOrder() {
        staffOf2020();

        // The doctor starts at the new employer a month after the old employment ends. The assignments of the old
        // employments end with them.
        ImportReport report = importPeople("2026-04-01",
                List.of(employment("E1", "nurse", null, "2026-04-01", null),
                        employmentAfter("E2", "nurse", "2026-04-01", "E1"),
                        employmentAfter("D2", "doctor", "2026-05-01", "D1"),
                        employment("D1", "doctor", null, "2026-04-01", null)),
                List.of(assignmentAfter("A2", "E2", "W", "2026-04-01"),
                        assignmentAfter("B2", "D2", "W", "2026-05-01")));

        assertEquals(List.of(), errors(report));
        assertEquals(new Counts(2, 2, 0), report.counts().get("employments"));
        assertEquals(new Counts(2, 0, 0), report.counts().get("assignments"));
        assertEquals(stored(ExternalId.Kind.EMPLOYMENT, "import", "D1"), employmentStore
                .find(stored(ExternalId.Kind.EMPLOYMENT, "import", "D2")).orElseThrow().continuesEmploymentId());
    }

    @Test
    void aNewRowFailsWhileTheOneItContinuesHasNotEnded() {
        staffOf2020();
        String e1 = stored(ExternalId.Kind.EMPLOYMENT, "import", "E1");
        String d1 = stored(ExternalId.Kind.EMPLOYMENT, "import", "D1");

        // The file does not end the employment of the nurse, and ends the one of the doctor after the new one
        // starts.
        ImportReport report = importPeople("2026-04-01",
                List.of(employmentAfter("E2", "nurse", "2026-04-01", "E1"),
                        employment("D1", "doctor", null, "2026-05-01", null),
                        employmentAfter("D2", "doctor", "2026-04-01", "D1")), null);

        assertEquals(List.of("employments[0] employment.continues_holds", "employments[2] employment.continues_holds"),
                errors(report));
        assertEquals(List.of(Map.of("id", e1, "date", "2026-04-01"), Map.of("id", d1, "date", "2026-04-01")),
                report.errors().stream().map(e -> e.error().params()).toList());
        assertEquals(new Counts(0, 1, 0), report.counts().get("employments"));
    }

    @Test
    void aLaterFileCannotOpenAgainWhatAnotherOneContinues() {
        staffOf2020();
        assertEquals(List.of(), errors(importPeople("2026-04-01",
                List.of(employment("E1", "nurse", null, "2026-04-01", null),
                        employmentAfter("E2", "nurse", "2026-04-01", "E1")), null)));

        // An earlier end leaves a gap, which is fine.
        assertEquals(List.of(), errors(importPeople("2026-04-01",
                List.of(employment("E1", "nurse", null, "2026-03-01", null)), null)));
        ImportReport reopened = importPeople("2026-04-01",
                List.of(employment("E1", "nurse", null, "2026-10-01", null)), null);

        assertEquals(List.of("employments[0] employment.continues_holds"), errors(reopened));
        assertEquals(List.of(stored(ExternalId.Kind.EMPLOYMENT, "import", "E1")),
                reopened.errors().stream().map(e -> e.error().params().get("id")).toList());
    }

    private void importTwoUnits() {
        importUnits("2026-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"));
    }

    @Test
    void aRowThatGivesAStoredUnitAnotherWorkLocationOrParentGivesTheReadsBelowItThatChanged() {
        refItems.insert(new RefItem("l", RefKind.WORK_LOCATION, "tokyo", ItemNames.of("x"), 0, Map.of(), true));
        importUnits("2026-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"), unit("T", "Team", "W"),
                unit("C", "Clinic", null));
        String team = stored(ExternalId.Kind.ORG_UNIT, "import", "T");

        ImportReport placed = rows("2026-05-01", "orgUnits", """
                {"externalId": "H", "workLocation": "tokyo"}
                """);
        ImportReport moved = rows("2026-06-01", "orgUnitParents", """
                {"hierarchy": "default", "orgUnitExternalId": "W", "parentExternalId": "C"}
                """);

        assertEquals(1, placed.writes().size());
        assertEquals(Set.of(new PropagatedChange(DerivedValue.ORG_UNIT_EFFECTIVE_WORK_LOCATION,
                stored(ExternalId.Kind.ORG_UNIT, "import", "W")),
                new PropagatedChange(DerivedValue.ORG_UNIT_EFFECTIVE_WORK_LOCATION, team)),
                Set.copyOf(placed.writes().getFirst().propagated()));
        assertEquals(List.of(List.of(new PropagatedChange(DerivedValue.ORG_UNIT_EFFECTIVE_WORK_LOCATION, team))),
                moved.writes().stream().map(ImportReport.Write::propagated).toList());
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
                first.writes().stream().map(write -> write.resourceType().name()).toList());
        assertTrue(first.writes().get(2) instanceof ImportReport.Write.OfPerson);
        assertEquals("fuhito/people/" + nurse.personId(), first.writes().get(2).path());
        assertTrue(first.writes().get(2).created());
        assertTrue(first.writes().get(3) instanceof ImportReport.Write.OfEmployment);
        assertTrue(first.writes().get(6) instanceof ImportReport.Write.OfAssignment);
    }

    @Test
    void readsWhatTheRowsOfAListNameWithOneReadForEachKind() {
        importTwoUnits();
        List<PersonRow> people = new ArrayList<>();
        List<EmploymentRow> employments = new ArrayList<>();
        List<AssignmentRow> assignments = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            people.add(new PersonRow(null, "P" + i, null, null, "ja", japanese("人 " + i), null, null));
            employments.add(new EmploymentRow(null, "E" + i, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, "P" + i, null, null, null));
            assignments.add(assignment("A" + i, "E" + i, "W", true, null));
        }
        int single = externalIdStore.singleFinds;
        int many = externalIdStore.manyFinds;

        ImportReport report = service.apply(new ImportDocument("2026-04-01", false, null, null, null, employments,
                assignments, people));

        assertEquals(List.of(), errors(report));
        // A new resource finds its own ID free among the IDs that its list read. The lists read the people, then the
        // employments and the people, then the assignments, the employments, and the org units.
        assertEquals(0, externalIdStore.singleFinds - single);
        assertEquals(6, externalIdStore.manyFinds - many);
        // An employment that continues one of an earlier row of its list finds it.
        assertEquals(List.of(), errors(importPeople("2027-04-01", List.of(
                new EmploymentRow(null, "E4", "nurse", null, null, null, null, null, null, null, null, "2025-04-01",
                        "2026-04-01", null, null, null, null, null, null, null, null),
                continuing("E5", "E4", null)), null)));
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
        // The end of the doctor ends the assignment of the doctor too.
        assertEquals(List.of("FUHITO_EMPLOYMENT /terms", "FUHITO_EMPLOYMENT /end", "FUHITO_ASSIGNMENT /end",
                "FUHITO_ASSIGNMENT /end", "FUHITO_ASSIGNMENT "), report.writes().stream()
                .map(w -> w.resourceType() + " " + w.path().substring(w.path().indexOf('/', 7) + 37)).toList());
        assertEquals(stored(ExternalId.Kind.ASSIGNMENT, "import", "A2"), report.writes().get(2).resourceId());

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
        // The write of the new employment shows it before the end gives it the reason.
        assertNull(((ImportReport.Write.OfEmployment) report.writes().get(1)).state().endReason());
        assertEquals("resignation", ((ImportReport.Write.OfEmployment) report.writes().get(2)).state().endReason());
        var stored = employmentStore.find(stored(ExternalId.Kind.EMPLOYMENT, "hr", "E9")).orElseThrow();
        assertEquals(LocalDate.of(2026, 4, 1), stored.period().to());
        assertEquals("r", stored.endReasonId());
        assertEquals(new Counts(0, 0, 1), service.apply(document).counts().get("employments"));
    }

    @Test
    void aRowThatChangesAStoredPersonSeesTheNewPeopleBeforeIt() {
        assertEquals(List.of(), errors(importPersons(ofUser("P1", "nurse", null))));

        ImportReport report = importPersons(ofUser("P2", "doctor", null), ofUser("P1", "doctor", null));

        assertEquals(List.of("people[1] person.user_taken"), errors(report));
    }

    @Test
    void aRowOfAssignmentsSeesTheWritesOfTheRowsBeforeIt() {
        importTwoUnits();
        importPeople("2026-04-01",
                List.of(employment("E1", "nurse", null, null, null), employment("E2", "doctor", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null), assignment("A2", "E2", "W", false, null)));

        ImportReport report = importPeople("2027-04-01", List.of(), List.of(
                assignment("A3", "E1", "H", true, null), assignment("A1", "E1", "W", true, null),
                assignment("A2", "E2", "W", true, null), assignment("A4", "E2", "H", true, null)));

        // A stored assignment cannot take the flag that a new one took before it, and a new one cannot take the
        // flag that a stored one took before it.
        assertEquals(List.of("assignments[1] assignment.primary_exists", "assignments[3] assignment.primary_exists"),
                errors(report));
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
                // A row with null for the work percent removes the stored one.
                ImportRows.clearing(new EmploymentRow(null, "E", "nurse", null, null, "part-time", "g2", "ON_LEAVE",
                        null, Map.of("shift", "night"), null, null, null), "workPercent"),
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
                        Map.of("room", "3"), null, null, null, null, null, "home", null),
                new AssignmentRow(null, "A", "E", "W", "director", "nurse", "support", true, true, all, 4,
                        Map.of("room", "3"), null, null, null, null, null, "home", "cc"));

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
                created.writes().stream().map(write -> write.resourceType().name()).toList());
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
        RefItemRow base = new RefItemRow("W", "UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null,
                Map.of("k", "v"), true);
        service.apply(doc("2026-04-01", false, null, List.of(base), null));

        assertEquals(new Counts(0, 0, 1),
                service.apply(doc("2026-04-01", false, null, List.of(base), null)).counts().get("refItems"));
        assertEquals(new Counts(0, 1, 0), service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("W", "UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null,
                        Map.of("k", "w"), true)), null))
                .counts().get("refItems"));
        assertEquals(new Counts(0, 1, 0), service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("W", "UNIT_TYPE", "ward", "en", ItemNames.english("Ward"), null,
                        Map.of("k", "w"), false)), null))
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

    // One list of a document as the JSON import reads it.
    private ImportReport rows(String effectiveDate, String list, String rows) {
        return service.apply(ImportRows.read(ImportDocument.class,
                "{\"effectiveDate\": \"" + effectiveDate + "\", \"" + list + "\": [" + rows + "]}"));
    }

    private static List<String> fields(ImportReport report) {
        return report.errors().stream()
                .map(e -> e.error().code() + " " + e.error().params().getOrDefault("field", ""))
                .toList();
    }

    private void item(RefKind kind, String code) {
        refItems.insert(new RefItem(code + "-id", kind, code, ItemNames.of(code), 0, Map.of(), true));
    }

    @Test
    void aRowOfAStoredUnitKeepsWhatItLeavesOutAndClearsWhatIsNull() {
        item(RefKind.UNIT_TYPE, "ward");
        rows("2026-04-01", "orgUnits", """
                {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}},
                {"externalId": "W", "primaryLanguage": "ja", "parentExternalId": "H", "code": "w1", "unitType": "ward",
                 "displayOrder": 2, "timeZone": "Asia/Tokyo", "attributes": {"beds": 10, "floor": 3},
                 "names": {"ja": {"name": "病棟", "shortName": "病"}, "en": {"name": "Ward"}}}
                """);
        String hospital = stored(ExternalId.Kind.ORG_UNIT, "import", "H");
        String ward = stored(ExternalId.Kind.ORG_UNIT, "import", "W");

        ImportReport kept = rows("2026-05-01", "orgUnits", """
                {"externalId": "W"}
                """);
        ImportReport cleared = rows("2026-06-01", "orgUnits", """
                {"externalId": "W", "code": null, "displayOrder": null, "timeZone": null,
                 "names": {"ja": {"shortName": null}, "en": null}, "attributes": {"floor": null, "beds": 12}}
                """);
        ImportReport root = rows("2026-07-01", "orgUnits", """
                {"externalId": "W", "parentExternalId": null, "unitType": null, "attributes": null}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("orgUnits"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("orgUnits"));
        OrgUnitState june = orgUnits.get(ward, "2026-06-01");
        assertNull(june.version().code());
        assertEquals(0, june.version().displayOrder());
        assertEquals(new Localized<>("ja", Map.of("ja", new OrgUnitName("病棟", "病", null),
                "en", new OrgUnitName("Ward", null, null))), orgUnits.get(ward, "2026-05-01").version().names());
        assertEquals(Localized.of("ja", new OrgUnitName("病棟", null, null)), june.version().names());
        assertEquals(Map.of("beds", 12), june.version().attributes());
        assertEquals("ward", june.unitType());
        assertEquals(hospital, june.parentId());
        assertNull(orgUnitStore.findUnit(ward).orElseThrow().timeZone());
        assertEquals(List.of(), errors(root));
        OrgUnitState july = orgUnits.get(ward, "2026-07-01");
        assertNull(july.parentId());
        assertNull(july.unitType());
        assertEquals(Map.of(), july.version().attributes());
    }

    @Test
    void aRowOfAUnitFailsForAnEmptyValueAndForNullOfAValueThatTheUnitMustHave() {
        importUnits("2026-04-01", unit("A", "A", null), unit("B", "B", null), unit("C", "C", null),
                unit("D", "D", null), unit("E", "E", null), unit("F", "F", null), unit("G", "G", null),
                unit("I", "I", null), unit("J", "J", null));

        ImportReport report = rows("2026-05-01", "orgUnits", """
                {"externalId": "A", "code": " "},
                {"externalId": "B", "names": {"ja": {"shortName": ""}}},
                {"externalId": "C", "attributes": {"note": ""}},
                {"externalId": "D", "attributes": {"tags": []}},
                {"externalId": "E", "validTo": "2027-04-01", "successorExternalIds": []},
                {"externalId": "F", "primaryLanguage": null},
                {"externalId": "G", "validFrom": null},
                {"externalId": "I", "names": {"ja": null}},
                {"externalId": "J", "names": null}
                """);

        assertEquals(List.of("invalid.blank code", "invalid.blank names.ja.shortName", "invalid.blank attributes.note",
                "invalid.blank attributes.tags", "invalid.blank successorExternalIds", "invalid.required primaryLanguage",
                "invalid.required validFrom", "invalid.primary_language ", "invalid.required names"), fields(report));
    }

    @Test
    void aTextThatCannotBeStoredFailsItsRowInEveryList() {
        ImportReport report = service.apply(ImportRows.read(ImportDocument.class, """
                {"effectiveDate": "2026-04-01",
                 "orgUnits": [{"externalId": "A", "code": "a\\u0000b", "primaryLanguage": "ja",
                   "names": {"ja": {"name": "A"}}}],
                 "people": [{"externalId": "P", "primaryLanguage": "ja", "names": {"ja": {"name": "\\ud800"}}}],
                 "employments": [{"externalId": "E", "personExternalId": "P", "staffNumber": "a\\udc00"}],
                 "assignments": [{"externalId": "S", "employmentExternalId": "E", "orgUnitExternalId": "A",
                   "attributes": {"a\\u0000": "x"}}]}
                """));

        assertEquals(List.of("invalid.character code", "invalid.character names.ja.name",
                "invalid.character staffNumber", "invalid.character attributes.a" + (char) 0), fields(report));
    }

    @Test
    void aNewUnitTakesNullAsNoValue() {
        ImportReport report = rows("2026-04-01", "orgUnits", """
                {"externalId": "N", "primaryLanguage": "ja", "names": {"ja": {"name": "新", "shortName": null},
                 "en": null}, "unitType": null, "parentExternalId": null, "attributes": {"beds": null},
                 "timeZone": null, "organizationAlias": null, "validFrom": null, "validTo": null,
                 "successorIds": null}
                """);

        assertEquals(List.of(), errors(report));
        OrgUnitState state = orgUnits.get(stored(ExternalId.Kind.ORG_UNIT, "import", "N"), "2026-04-01");
        assertEquals(Localized.of("ja", new OrgUnitName("新", null, null)), state.version().names());
        assertEquals(Map.of(), state.version().attributes());
        assertNull(state.parentId());
        assertEquals("2026-04-01", state.version().period().from().toString());
    }

    @Test
    void aRowOfAStoredUnitRemovesItsEndWithNull() {
        rows("2026-04-01", "orgUnits", """
                {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}, "validTo": "2027-04-01"}
                """);
        String hospital = stored(ExternalId.Kind.ORG_UNIT, "import", "H");

        ImportReport kept = rows("2026-05-01", "orgUnits", """
                {"externalId": "H"}
                """);
        ImportReport removed = rows("2026-05-01", "orgUnits", """
                {"externalId": "H", "validTo": null}
                """);
        ImportReport again = rows("2026-05-01", "orgUnits", """
                {"externalId": "H", "validTo": null}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("orgUnits"));
        assertEquals(new Counts(0, 1, 0), removed.counts().get("orgUnits"));
        assertEquals(new Counts(0, 0, 1), again.counts().get("orgUnits"));
        assertNull(orgUnitStore.versions(hospital).getLast().period().to());
    }

    @Test
    void aRowOfAUnitWithSuccessorsCannotClearThem() {
        importUnits("2020-04-01", ending("OLD", "2026-04-01", List.of("NEW"), null), starting("NEW", "2026-04-01"));

        ImportReport kept = rows("2020-04-01", "orgUnits", """
                {"externalId": "OLD", "successorExternalIds": ["NEW"]}
                """);
        ImportReport cleared = rows("2020-04-01", "orgUnits", """
                {"externalId": "OLD", "successorExternalIds": null}
                """);

        assertEquals(List.of(), errors(kept));
        assertEquals(new Counts(0, 0, 1), kept.counts().get("orgUnits"));
        assertEquals(List.of("orgUnits[0] import.successors_differ"), errors(cleared));
    }

    @Test
    void aRowOfAParentKeepsTheParentThatItLeavesOutAndTakesTheUnitOutWithNull() {
        importUnits("2026-04-01", unit("P", "P", null), unit("C", "C", null));
        orgUnitStore.hierarchyCodes.put("project-id", "project");
        String child = stored(ExternalId.Kind.ORG_UNIT, "import", "C");

        ImportReport placed = rows("2026-04-01", "orgUnitParents", """
                {"hierarchy": "project", "orgUnitExternalId": "C", "parentExternalId": "P"}
                """);
        ImportReport kept = rows("2026-05-01", "orgUnitParents", """
                {"hierarchy": "project", "orgUnitExternalId": "C"}
                """);
        ImportReport out = rows("2026-06-01", "orgUnitParents", """
                {"hierarchy": "project", "orgUnitExternalId": "C", "parentExternalId": null}
                """);
        ImportReport blank = rows("2026-06-01", "orgUnitParents", """
                {"hierarchy": "project", "orgUnitExternalId": "C", "parentId": ""}
                """);

        assertEquals(new Counts(0, 1, 0), placed.counts().get("orgUnitParents"));
        assertEquals(new Counts(0, 0, 1), kept.counts().get("orgUnitParents"));
        assertEquals(new Counts(0, 1, 0), out.counts().get("orgUnitParents"));
        assertEquals(stored(ExternalId.Kind.ORG_UNIT, "import", "P"),
                orgUnits.get(child, "2026-05-01", "project").parentId());
        assertNull(orgUnits.get(child, "2026-06-01", "project").parentId());
        assertEquals(List.of("invalid.blank parentId"), fields(blank));
    }

    @Test
    void aRowOfAStoredKindKeepsItsNamesAndNullRemovesALanguage() {
        ImportReport created = rows("2026-04-01", "refKinds", """
                {"externalId": "SITE", "code": "site", "primaryLanguage": "ja",
                 "names": {"ja": {"name": "拠点"}, "en": {"name": "Site"}, "zh": null}}
                """);
        ImportReport kept = rows("2026-04-01", "refKinds", """
                {"externalId": "SITE"}
                """);
        ImportReport cleared = rows("2026-04-01", "refKinds", """
                {"externalId": "SITE", "names": {"en": null}}
                """);

        assertEquals(new Counts(1, 0, 0), created.counts().get("refKinds"));
        assertEquals(new Counts(0, 0, 1), kept.counts().get("refKinds"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("refKinds"));
        assertEquals(Localized.of("ja", new RefKindName("拠点")), kinds.get("site").names());
        assertEquals(List.of("invalid.required code"), fields(rows("2026-04-01", "refKinds", """
                {"externalId": "SITE", "code": null}
                """)));
        assertEquals(List.of("invalid.blank names.ja.name"), fields(rows("2026-04-01", "refKinds", """
                {"externalId": "SITE", "names": {"ja": {"name": " "}}}
                """)));
        assertEquals(List.of("invalid.required primaryLanguage"), fields(rows("2026-04-01", "refKinds", """
                {"externalId": "SITE", "primaryLanguage": null}
                """)));
    }

    @Test
    void aRowOfAStoredDefinitionKeepsWhatItLeavesOut() {
        rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D", "target": "ORG_UNIT", "name": "tags", "type": "STRING", "multiple": true,
                 "required": null, "refKind": null}
                """);
        String id = stored(ExternalId.Kind.ATTRIBUTE_DEFINITION, "import", "D");

        ImportReport kept = rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D"}
                """);
        ImportReport cleared = rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D", "multiple": null}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("attributeDefinitions"));
        assertEquals(List.of("import.value_fixed multiple"), fields(cleared));
        assertTrue(definitions.get(id).multiple());
        assertEquals("STRING", definitions.get(id).type().name());
        assertEquals(List.of("invalid.required name"), fields(rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D", "name": null}
                """)));
        assertEquals(List.of("invalid.required type"), fields(rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D", "type": null}
                """)));
        assertEquals(List.of("invalid.blank refKind"), fields(rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D", "refKind": ""}
                """)));
    }

    @Test
    void aRowOfARequiredDefinitionFillsTheRowsWithNoValue() {
        values.add(AttributeDefinition.Target.PERSON, "p-2", Map.of());
        values.add(AttributeDefinition.Target.PERSON, "p-1", Map.of("beds", 1));
        values.add(AttributeDefinition.Target.PERSON, "p-3", Map.of());
        String row = """
                {"externalId": "D", "target": "PERSON", "name": "beds", "type": "NUMBER", "required": true%s}""";

        assertEquals(List.of("attribute.fill_value_required "), fields(rows("2026-04-01", "attributeDefinitions",
                row.formatted(""))));
        assertEquals(List.of("invalid.not_together fillValue"), fields(rows("2026-04-01", "attributeDefinitions",
                row.formatted(", \"fillValue\": 3").replace("\"required\": true", "\"required\": false"))));
        assertEquals(List.of("attribute.bad_value "), fields(rows("2026-04-01", "attributeDefinitions",
                row.formatted(", \"fillValue\": \"3\""))));
        ImportReport filled = rows("2026-04-01", "attributeDefinitions", row.formatted(", \"fillValue\": 3"));

        String id = stored(ExternalId.Kind.ATTRIBUTE_DEFINITION, "import", "D");
        assertEquals(List.of("fuhito/attribute-definitions/" + id, "fuhito/people/p-2", "fuhito/people/p-3"),
                filled.writes().stream().map(ImportReport.Write::path).toList());
        assertEquals(List.of(Map.of("beds", 3), Map.of("beds", 1), Map.of("beds", 3)),
                values.rows.stream().map(MemoryAttributeValueStore.Row::attributes).toList());
        assertEquals(Map.of("p-1", 1L, "p-2", 2L, "p-3", 2L), values.versions);

        // A row that changes nothing still gives a value to fill that fits.
        assertEquals(List.of("attribute.bad_value "), fields(rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D", "fillValue": [3]}""")));
        assertEquals(new Counts(0, 0, 1), rows("2026-04-01", "attributeDefinitions", """
                {"externalId": "D", "fillValue": 4}""").counts().get("attributeDefinitions"));
        for (String fixed : List.of("type", "multiple", "refKind")) {
            String value = Map.of("type", "\"STRING\"", "multiple", "true", "refKind", "\"GRADE\"").get(fixed);
            assertEquals(List.of("import.value_fixed " + fixed), fields(rows("2026-04-01", "attributeDefinitions",
                    "{\"externalId\": \"D\", \"" + fixed + "\": " + value + "}")), fixed);
        }
        assertEquals(Map.of("p-1", 1L, "p-2", 2L, "p-3", 2L), values.versions);
    }

    @Test
    void aRowOfAStoredItemKeepsWhatItLeavesOutAndMergesItsNamesAndAttributes() {
        rows("2026-04-01", "refItems", """
                {"externalId": "T", "kind": "UNIT_TYPE", "code": "company", "primaryLanguage": "ja",
                 "names": {"ja": {"name": "会社"}, "en": {"name": "Company"}}, "displayOrder": 3,
                 "attributes": {"a": 1, "b": 2}, "active": false, "legalEntity": true}
                """);

        ImportReport kept = rows("2026-04-01", "refItems", """
                {"externalId": "T"}
                """);
        ImportReport cleared = rows("2026-04-01", "refItems", """
                {"externalId": "T", "names": {"en": null}, "attributes": {"a": null}, "displayOrder": null,
                 "active": null, "legalEntity": null}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("refItems"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("refItems"));
        RefItem item = refItems.findByCode(RefKind.UNIT_TYPE, "company").orElseThrow();
        assertEquals(Localized.of("ja", new RefItemName("会社")), item.names());
        assertEquals(0, item.displayOrder());
        assertEquals(Map.of("b", 2), item.attributes());
        assertTrue(item.active());
        assertFalse(item.legalEntity());
        assertEquals(List.of("invalid.required kind"), fields(rows("2026-04-01", "refItems", """
                {"externalId": "T", "kind": null}
                """)));
        assertEquals(List.of("invalid.blank code"), fields(rows("2026-04-01", "refItems", """
                {"externalId": "T", "code": " "}
                """)));
        assertEquals(List.of("invalid.required names.ja.name"), fields(rows("2026-04-01", "refItems", """
                {"externalId": "T", "names": {"ja": {"name": null}}}
                """)));
    }

    @Test
    void aRowOfAStoredHierarchyKeepsItsName() {
        rows("2026-04-01", "hierarchies", """
                {"externalId": "P", "code": "project", "name": "Project"}
                """);

        assertEquals(new Counts(0, 0, 1), rows("2026-04-01", "hierarchies", """
                {"externalId": "P"}
                """).counts().get("hierarchies"));
        assertEquals(List.of("invalid.required name"), fields(rows("2026-04-01", "hierarchies", """
                {"externalId": "P", "name": null}
                """)));
        assertEquals(List.of("invalid.blank name"), fields(rows("2026-04-01", "hierarchies", """
                {"externalId": "P", "name": ""}
                """)));
        assertEquals(List.of("invalid.required code"), fields(rows("2026-04-01", "hierarchies", """
                {"externalId": "P", "code": null}
                """)));
    }

    @Test
    void aRowOfAStoredPersonKeepsWhatItLeavesOutAndMergesItsNamesAndAttributes() {
        rows("2026-04-01", "people", """
                {"externalId": "P", "username": "nurse", "primaryLanguage": "ja",
                 "names": {"ja": {"name": "山田", "phoneticName": "やまだ"}, "en": {"name": "Yamada"}},
                 "attributes": {"a": 1, "b": 2}}
                """);
        String id = stored(ExternalId.Kind.PERSON, "import", "P");

        ImportReport kept = rows("2026-04-01", "people", """
                {"externalId": "P"}
                """);
        ImportReport cleared = rows("2026-04-01", "people", """
                {"externalId": "P", "names": {"ja": {"phoneticName": null}, "en": null}, "attributes": {"a": null}}
                """);
        Person afterClear = personStore.find(id).orElseThrow();
        ImportReport unlinked = rows("2026-04-01", "people", """
                {"externalId": "P", "username": null}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("people"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("people"));
        assertEquals(Localized.of("ja", new PersonName("山田", null)), afterClear.names());
        assertEquals(Map.of("b", 2), afterClear.attributes());
        assertEquals("user-nurse", afterClear.userId());
        assertEquals(new Counts(0, 1, 0), unlinked.counts().get("people"));
        assertNull(personStore.find(id).orElseThrow().userId());
        assertEquals(List.of("invalid.blank primaryLanguage"), fields(rows("2026-04-01", "people", """
                {"externalId": "P", "primaryLanguage": ""}
                """)));
    }

    @Test
    void aRowThatChangesTheUserOfAStoredPersonGivesTheRecordsWhoseReadChanged() {
        rows("2026-04-01", "people", """
                {"externalId": "P", "username": "nurse", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}}}
                """);
        String id = stored(ExternalId.Kind.PERSON, "import", "P");
        List<PropagatedChange> records = List.of(new PropagatedChange(DerivedValue.EMPLOYMENT_USER_ID, "e1"));
        personStore.readsOfUser.put(id, records);

        ImportReport renamed = rows("2026-04-01", "people", """
                {"externalId": "P", "names": {"ja": {"name": "山田 花子"}}}
                """);
        ImportReport unlinked = rows("2026-04-01", "people", """
                {"externalId": "P", "username": null}
                """);

        assertEquals(List.of(List.of()), renamed.writes().stream().map(ImportReport.Write::propagated).toList());
        assertEquals(List.of(records), unlinked.writes().stream().map(ImportReport.Write::propagated).toList());
    }

    @Test
    void aRowOfAStoredEmploymentKeepsWhatItLeavesOutAndClearsWhatIsNull() {
        importTwoUnits();
        item(RefKind.EMPLOYMENT_TYPE, "full-time");
        item(RefKind.GRADE, "g1");
        item(RefKind.LEAVE_REASON, "sick");
        rows("2026-04-01", "employments", """
                {"externalId": "E", "username": "nurse", "employmentType": "full-time", "grade": "g1",
                 "status": "ON_LEAVE", "leaveReason": "sick", "workPercent": 80, "contractEndDate": "2027-03-31",
                 "attributes": {"a": 1, "b": 2}}
                """);
        String id = stored(ExternalId.Kind.EMPLOYMENT, "import", "E");

        ImportReport kept = rows("2026-05-01", "employments", """
                {"externalId": "E"}
                """);
        ImportReport cleared = rows("2026-06-01", "employments", """
                {"externalId": "E", "grade": null, "workPercent": null, "contractEndDate": null,
                 "attributes": {"a": null}}
                """);
        ImportReport back = rows("2026-07-01", "employments", """
                {"externalId": "E", "status": "ACTIVE"}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("employments"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("employments"));
        EmploymentView.TermsView june = termsOn(id, "2026-06-01");
        assertEquals("full-time", june.employmentType());
        assertNull(june.grade());
        assertEquals("sick", june.leaveReason());
        assertEquals(EmploymentStatus.ON_LEAVE, june.terms().status());
        assertNull(june.terms().workPercent());
        assertNull(june.terms().contractEndDate());
        assertEquals(Map.of("b", 2), june.terms().attributes());
        // A status that is not a leave drops the reason that the row leaves out.
        assertEquals(List.of(), errors(back));
        assertNull(termsOn(id, "2026-07-01").leaveReason());
        assertEquals(List.of("invalid.required validFrom"), fields(rows("2026-07-01", "employments", """
                {"externalId": "E", "validFrom": null}
                """)));
        assertEquals(List.of("invalid.required username"), fields(rows("2026-07-01", "employments", """
                {"externalId": "E", "username": null}
                """)));
        assertEquals(List.of("invalid.blank grade"), fields(rows("2026-07-01", "employments", """
                {"externalId": "E", "grade": ""}
                """)));
        assertEquals(new Counts(0, 0, 1), rows("2026-07-01", "employments", """
                {"externalId": "E", "externalEmployer": null, "continuesEmploymentId": null}
                """).counts().get("employments"));
    }

    private EmploymentView.TermsView termsOn(String employmentId, String date) {
        return employments.get(employmentId).terms().stream()
                .filter(t -> t.terms().period().contains(LocalDate.parse(date)))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aLaterEndKeepsTheEndReasonAndNullRemovesTheEndWithItsReason() {
        importTwoUnits();
        item(RefKind.END_REASON, "moved");
        item(RefKind.END_REASON, "retired");
        rows("2026-04-01", "employments", """
                {"externalId": "E", "username": "nurse", "validTo": "2027-04-01", "endReason": "moved"}
                """);
        String id = stored(ExternalId.Kind.EMPLOYMENT, "import", "E");

        ImportReport later = rows("2026-04-01", "employments", """
                {"externalId": "E", "validTo": "2027-05-01"}
                """);
        String reason = employments.get(id).endReason();
        ImportReport other = rows("2026-04-01", "employments", """
                {"externalId": "E", "endReason": "retired"}
                """);
        ImportReport removed = rows("2026-04-01", "employments", """
                {"externalId": "E", "validTo": null}
                """);

        assertEquals(new Counts(0, 1, 0), later.counts().get("employments"));
        assertEquals("moved", reason);
        assertEquals(new Counts(0, 1, 0), other.counts().get("employments"));
        assertEquals(new Counts(0, 1, 0), removed.counts().get("employments"));
        assertNull(employmentStore.find(id).orElseThrow().period().to());
        assertNull(employments.get(id).endReason());
    }

    @Test
    void aRowWithAnEndReasonAndNoEndFails() {
        importTwoUnits();
        item(RefKind.END_REASON, "moved");
        rows("2026-04-01", "employments", """
                {"externalId": "E", "username": "nurse", "validTo": "2027-04-01", "endReason": "moved"},
                {"externalId": "F", "username": "doctor"}
                """);
        String ended = stored(ExternalId.Kind.EMPLOYMENT, "import", "E");
        String open = stored(ExternalId.Kind.EMPLOYMENT, "import", "F");

        ImportReport report = rows("2026-04-01", "employments", """
                {"externalId": "G", "username": "doctor", "validFrom": "2028-04-01", "endReason": "moved"},
                {"externalId": "E", "validTo": null, "endReason": "moved"},
                {"externalId": "F", "endReason": "moved"}
                """);

        assertEquals(List.of("employments[0] employment.end_reason_without_end",
                "employments[1] employment.end_reason_without_end", "employments[2] employment.end_reason_without_end"),
                errors(report));
        assertEquals(Map.of("endReason", "moved"), report.errors().getFirst().error().params());
        assertTrue(externalIdStore.idOf(ExternalId.Kind.EMPLOYMENT, "import", "G").isEmpty());
        assertEquals(LocalDate.parse("2027-04-01"), employmentStore.find(ended).orElseThrow().period().to());
        assertEquals("moved", employments.get(ended).endReason());
        assertNull(employments.get(open).endReason());
    }

    @Test
    void aRowOfAStoredAssignmentKeepsWhatItLeavesOutAndClearsWhatIsNull() {
        importTwoUnits();
        item(RefKind.JOB_TITLE, "chief");
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)), null);
        rows("2026-04-01", "assignments", """
                {"externalId": "A", "employmentExternalId": "E1", "orgUnitExternalId": "W", "jobTitle": "chief",
                 "head": true, "primary": true, "allocationPercent": 50, "displayOrder": 2,
                 "attributes": {"a": 1, "b": 2}}
                """);
        String id = stored(ExternalId.Kind.ASSIGNMENT, "import", "A");

        ImportReport kept = rows("2026-05-01", "assignments", """
                {"externalId": "A"}
                """);
        ImportReport cleared = rows("2026-06-01", "assignments", """
                {"externalId": "A", "jobTitle": null, "head": null, "allocationPercent": null, "displayOrder": null,
                 "attributes": {"a": null}}
                """);
        ImportReport ended = rows("2026-06-01", "assignments", """
                {"externalId": "A", "validTo": "2026-09-01"}
                """);
        ImportReport open = rows("2026-06-01", "assignments", """
                {"externalId": "A", "validTo": null}
                """);
        ImportReport again = rows("2026-06-01", "assignments", """
                {"externalId": "A", "validTo": null}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("assignments"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("assignments"));
        AssignmentView.Row june = assignments.get(id).rows().getLast();
        assertNull(june.jobTitle());
        assertFalse(june.row().head());
        assertTrue(june.row().primary());
        assertNull(june.row().allocationPercent());
        assertEquals(0, june.row().displayOrder());
        assertEquals(Map.of("b", 2), june.row().attributes());
        assertEquals(new Counts(0, 1, 0), ended.counts().get("assignments"));
        assertEquals(new Counts(0, 1, 0), open.counts().get("assignments"));
        assertEquals(new Counts(0, 0, 1), again.counts().get("assignments"));
        assertNull(assignments.get(id).period().to());
        assertEquals(List.of("invalid.required orgUnitExternalId"), fields(rows("2026-06-01", "assignments", """
                {"externalId": "A", "orgUnitExternalId": null}
                """)));
        assertEquals(List.of("invalid.required validFrom"), fields(rows("2026-06-01", "assignments", """
                {"externalId": "A", "validFrom": null}
                """)));
        assertEquals(List.of("invalid.blank occupation"), fields(rows("2026-06-01", "assignments", """
                {"externalId": "A", "occupation": ""}
                """)));
    }

    @Test
    void aRowOfAStoredLineKeepsItsEndAndCannotRemoveIt() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", true, null)));
        service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null,
                List.of(new PersonRow(null, "P3", null, null, "ja", japanese("Chief"), null, null))));
        rows("2026-04-01", "reportingLines", """
                {"externalId": "L1", "assignmentExternalId": "A1", "managerPersonExternalId": "P3", "kind": "DOTTED",
                 "validTo": "2026-10-01"},
                {"externalId": "L2", "assignmentExternalId": "A1", "managerUsername": "doctor"}
                """);

        assertEquals(new Counts(0, 0, 2), rows("2026-05-01", "reportingLines", """
                {"externalId": "L1"},
                {"externalId": "L2", "validTo": null}
                """).counts().get("reportingLines"));
        assertEquals(List.of("import.end_kept "), fields(rows("2026-05-01", "reportingLines", """
                {"externalId": "L1", "validTo": null}
                """)));
        assertEquals(List.of("import.value_fixed kind"), fields(rows("2026-05-01", "reportingLines", """
                {"externalId": "L1", "kind": null}
                """)));
        assertEquals(List.of("invalid.required managerPersonExternalId"), fields(rows("2026-05-01",
                "reportingLines", """
                {"externalId": "L1", "managerPersonExternalId": null}
                """)));
        assertEquals(List.of("invalid.required assignmentExternalId"), fields(rows("2026-05-01",
                "reportingLines", """
                {"externalId": "L1", "assignmentId": null}
                """)));
        assertEquals(List.of("invalid.blank kind"), fields(rows("2026-05-01", "reportingLines", """
                {"externalId": "L2", "kind": ""}
                """)));
    }

    @Test
    void aRowOfAStoredRoleKeepsItsEndAndCannotRemoveIt() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)), null);
        item(RefKind.UNIT_ROLE, "approver");
        rows("2026-04-01", "unitRoles", """
                {"externalId": "R1", "employmentExternalId": "E1", "orgUnitExternalId": "H", "role": "approver",
                 "includesDescendants": true, "validTo": "2026-10-01"},
                {"externalId": "R2", "employmentExternalId": "E1", "orgUnitExternalId": "W", "role": "approver"}
                """);

        assertEquals(new Counts(0, 0, 2), rows("2026-05-01", "unitRoles", """
                {"externalId": "R1"},
                {"externalId": "R2", "validTo": null}
                """).counts().get("unitRoles"));
        assertEquals(List.of("import.end_kept "), fields(rows("2026-05-01", "unitRoles", """
                {"externalId": "R1", "validTo": null}
                """)));
        assertEquals(List.of("import.value_fixed includesDescendants"), fields(rows("2026-05-01", "unitRoles", """
                {"externalId": "R1", "includesDescendants": null}
                """)));
        assertEquals(List.of("invalid.required role"), fields(rows("2026-05-01", "unitRoles", """
                {"externalId": "R1", "role": null}
                """)));
        assertEquals(List.of("invalid.required employmentExternalId"), fields(rows("2026-05-01", "unitRoles", """
                {"externalId": "R1", "employmentExternalId": null}
                """)));
        assertEquals(List.of("invalid.required orgUnitExternalId"), fields(rows("2026-05-01", "unitRoles", """
                {"externalId": "R1", "orgUnitId": null}
                """)));
        assertEquals(List.of("invalid.blank role"), fields(rows("2026-05-01", "unitRoles", """
                {"externalId": "R2", "role": ""}
                """)));
    }

    @Test
    void aRowOfAStoredQualificationKeepsWhatItLeavesOutAndClearsWhatIsNull() {
        item(RefKind.QUALIFICATION, "license");
        service.apply(new ImportDocument("2026-04-01", false, null, null, null, null, null,
                List.of(new PersonRow(null, "P1", null, null, "ja", japanese("Ann"), null, null))));
        rows("2026-04-01", "qualifications", """
                {"externalId": "Q1", "personExternalId": "P1", "kind": "license", "number": "123",
                 "validFrom": "2020-04-01", "validTo": "2030-04-01", "attributes": {"a": 1, "b": 2}}
                """);
        String id = stored(ExternalId.Kind.QUALIFICATION, "import", "Q1");

        ImportReport kept = rows("2026-04-01", "qualifications", """
                {"externalId": "Q1"}
                """);
        ImportReport cleared = rows("2026-04-01", "qualifications", """
                {"externalId": "Q1", "number": null, "validTo": null, "attributes": {"a": null}}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("qualifications"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("qualifications"));
        Qualification q = qualificationStore.find(id).orElseThrow();
        assertNull(q.number());
        assertNull(q.period().to());
        assertEquals("2020-04-01", q.period().from().toString());
        assertEquals(Map.of("b", 2), q.attributes());
        assertEquals(List.of("invalid.required validFrom"), fields(rows("2026-04-01", "qualifications", """
                {"externalId": "Q1", "validFrom": null}
                """)));
        assertEquals(List.of("invalid.required personExternalId"), fields(rows("2026-04-01", "qualifications", """
                {"externalId": "Q1", "personExternalId": null}
                """)));
        assertEquals(List.of("invalid.blank number"), fields(rows("2026-04-01", "qualifications", """
                {"externalId": "Q1", "number": ""}
                """)));
    }

    @Test
    void aRowOfAStoredChangeOrderKeepsWhatItLeavesOutAndClearsWhatIsNull() {
        rows("2026-04-01", "changeOrders", """
                {"externalId": "O1", "number": "2026-01", "title": "異動", "note": "memo", "orderDate": "2026-03-01",
                 "effectiveDate": "2026-04-01", "approverUsername": "doctor", "externalReference": "wf-1"}
                """);

        ImportReport kept = rows("2026-05-01", "changeOrders", """
                {"externalId": "O1"}
                """);
        ImportReport cleared = rows("2026-05-01", "changeOrders", """
                {"externalId": "O1", "note": null, "approverUsername": null, "effectiveDate": null}
                """);

        assertEquals(new Counts(0, 0, 1), kept.counts().get("changeOrders"));
        assertEquals(new Counts(0, 1, 0), cleared.counts().get("changeOrders"));
        ChangeOrder order = orderStore.findByNumber("2026-01").orElseThrow();
        assertNull(order.note());
        assertNull(order.approverUserId());
        assertEquals("2026-05-01", order.effectiveDate().toString());
        assertEquals("2026-03-01", order.orderDate().toString());
        assertEquals("wf-1", order.externalReference());
        assertEquals(List.of("invalid.required title"), fields(rows("2026-05-01", "changeOrders", """
                {"externalId": "O1", "title": null}
                """)));
        assertEquals(List.of("invalid.blank note"), fields(rows("2026-05-01", "changeOrders", """
                {"externalId": "O1", "note": " "}
                """)));
    }
}
