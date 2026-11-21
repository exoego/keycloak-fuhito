package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKindName;
import net.exoego.keycloak.fuhito.service.ImportDocument.AttributeDefinitionRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.HierarchyRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefItemRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefKindRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * The kinds of reference data, the definitions of attributes, the reference items, and the hierarchies of one
 * document. A row names its stored thing by ID or by its ID in the system of the row. A code is a value, which never
 * changes.
 */
final class SetupImport {

    private static final String REF_ITEMS = "refItems";
    private static final String REF_KINDS = "refKinds";
    private static final String ATTRIBUTE_DEFINITIONS = "attributeDefinitions";
    private static final String HIERARCHIES = "hierarchies";

    private final ImportService.Setup setup;
    private final RefItemService refItemService;
    private final RefItemStore refItems;
    private final ExternalIdService externalIds;
    private final LocalDate effectiveDate;
    private final String defaultSource;
    private final ImportReport report;
    private final Runnable rowDone;

    SetupImport(ImportService.Setup setup, RefItemService refItemService, RefItemStore refItems,
            ExternalIdService externalIds, LocalDate effectiveDate, String defaultSource, ImportReport report,
            Runnable rowDone) {
        this.rowDone = rowDone;
        this.setup = setup;
        this.refItemService = refItemService;
        this.refItems = refItems;
        this.externalIds = externalIds;
        this.effectiveDate = effectiveDate;
        this.defaultSource = defaultSource;
        this.report = report;
    }

    void refKinds(List<RefKindRow> rows) {
        ImportMatch match = new ImportMatch(externalIds, ExternalId.Kind.REF_KIND);
        each(REF_KINDS, rows, row -> refKind(row, match));
    }

    void attributeDefinitions(List<AttributeDefinitionRow> rows) {
        ImportMatch match = new ImportMatch(externalIds, ExternalId.Kind.ATTRIBUTE_DEFINITION);
        each(ATTRIBUTE_DEFINITIONS, rows, row -> attributeDefinition(row, match));
    }

    void refItems(List<RefItemRow> rows) {
        ImportMatch match = new ImportMatch(externalIds, ExternalId.Kind.REF_ITEM);
        AttributeRules rules = refItemService.attributeRules().remembering();
        each(REF_ITEMS, rows, row -> refItem(row, match, rules));
    }

    void hierarchies(List<HierarchyRow> rows) {
        ImportMatch match = new ImportMatch(externalIds, ExternalId.Kind.HIERARCHY);
        each(HIERARCHIES, rows, row -> hierarchy(row, match));
    }

    private <R extends Record> void each(String list, List<R> rows, Function<R, Outcome> apply) {
        report.startList(list);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(list, apply.apply(EmptyValues.checked(rows.get(i))));
            } catch (FuhitoException e) {
                report.error(list, i, e);
            }
            rowDone.run();
        }
    }

    private String source(String source) {
        return Checks.code("source", source == null ? defaultSource : source);
    }

    private Outcome refKind(RefKindRow row, ImportMatch match) {
        RealmKindService kinds = setup.kinds();
        String source = source(row.source());
        Optional<String> stored = match.stored(source, row.id(), row.externalId(),
                id -> kinds.codeOf(id).isPresent());
        if (stored.isEmpty()) {
            Map<String, RefKindName> names = Merged.names(row, row.names(), null, Merged.KIND_NAMES);
            report.write(new Write.OfRefKind(true, match.create(source, row.externalId(),
                    () -> kinds.create(row.code(), row.primaryLanguage(), names), RealmKindService.View::id)));
            return Outcome.CREATED;
        }
        String id = stored.get();
        ImportMatch.source(id, null, row.source(), row.externalId());
        String code = ImportMatch.fixed(row, id, "code", kinds.codeOf(id).orElseThrow(), row.code());
        Localized<RefKindName> before = kinds.get(code).names();
        String primaryLanguage = row.or("primaryLanguage", row.primaryLanguage(), before.primaryLanguage());
        Map<String, RefKindName> names = Merged.names(row, row.names(), before.byLanguage(), Merged.KIND_NAMES);
        if (RefKindName.check(primaryLanguage, names).equals(before)) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfRefKind(false, kinds.rename(code, primaryLanguage, names)));
        return Outcome.CHANGED;
    }

    private Outcome attributeDefinition(AttributeDefinitionRow row, ImportMatch match) {
        AttributeDefinitionService definitions = setup.definitions();
        String source = source(row.source());
        Optional<String> stored = match.stored(source, row.id(), row.externalId(),
                id -> definitions.find(id).isPresent());
        if (stored.isEmpty()) {
            AttributeDefinitionService.Written written = match.create(source, row.externalId(),
                    () -> definitions.create(row.target(), row.name(), row.type(), row.refKind(), row.multiple(),
                            row.required(), row.fillValue()), each -> each.definition().id());
            report.write(new Write.OfAttributeDefinition(true, written.definition()));
            written.values().forEach(report::write);
            return Outcome.CREATED;
        }
        AttributeDefinition before = definitions.get(stored.get());
        ImportMatch.source(before.id(), null, row.source(), row.externalId());
        ImportMatch.fixed(row, before.id(), "target", before.target().name(), row.target());
        ImportMatch.fixed(row, before.id(), "name", before.name(), row.name());
        ImportMatch.fixed(row, before.id(), "type", before.type().name(), row.type());
        // A definition with no kind or with no list has null, so a row that clears one of these may give the stored
        // value.
        fixed(before.id(), "refKind", before.refKind(), row.or("refKind", row.refKind(), before.refKind()));
        fixed(before.id(), "multiple", before.multiple(),
                Boolean.TRUE.equals(row.or("multiple", row.multiple(), before.multiple())));
        boolean required = Boolean.TRUE.equals(row.or("required", row.required(), before.required()));
        if (before.required() == required) {
            definitions.fillValue(before, row.fillValue());
            return Outcome.UNCHANGED;
        }
        AttributeDefinitionService.Written written = definitions.replace(before.id(), null, null, null, required,
                row.fillValue());
        report.write(new Write.OfAttributeDefinition(false, written.definition()));
        written.values().forEach(report::write);
        return Outcome.CHANGED;
    }

    private static void fixed(String id, String field, Object stored, Object given) {
        if (!Objects.equals(stored, given)) {
            throw FuhitoException.conflict("import.value_fixed", Map.of("id", id, "field", field));
        }
    }

    private Outcome hierarchy(HierarchyRow row, ImportMatch match) {
        HierarchyService hierarchies = setup.hierarchies();
        String source = source(row.source());
        Optional<String> stored = match.stored(source, row.id(), row.externalId(),
                id -> hierarchies.findById(id).isPresent());
        if (stored.isEmpty()) {
            report.write(new Write.OfHierarchy(true, match.create(source, row.externalId(),
                    () -> hierarchies.create(row.code(), row.name()), Hierarchy::id)));
            return Outcome.CREATED;
        }
        Hierarchy before = hierarchies.get(stored.get());
        ImportMatch.source(before.id(), null, row.source(), row.externalId());
        ImportMatch.fixed(row, before.id(), "code", before.code(), row.code());
        String name = row.or("name", row.name(), before.name());
        if (Checks.name("name", name).equals(before.name())) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfHierarchy(false, hierarchies.rename(before.id(), name)));
        return Outcome.CHANGED;
    }

    // A row compares with the names of its date, so the same row on the same date changes nothing. Rows of one item
    // with other dates give the names of each date. A mark that the row clears is off.
    private Outcome refItem(RefItemRow row, ImportMatch match, AttributeRules rules) {
        String source = source(row.source());
        LocalDate validFrom = Checks.optionalDate("validFrom", row.validFrom());
        LocalDate date = validFrom == null ? effectiveDate : validFrom;
        Optional<String> stored = match.stored(source, row.id(), row.externalId(), date.toString(),
                id -> refItems.find(id).isPresent());
        if (stored.isEmpty()) {
            RefItemService.Marks marks = new RefItemService.Marks(row.legalEntity(), row.actingHead(), row.lateral(),
                    row.rollup());
            Map<String, RefItemName> names = Merged.names(row, row.names(), null, Merged.ITEM_NAMES);
            RefItem item = match.create(source, row.externalId(), () -> refItemService.create(
                    Checks.required("kind", row.kind()), row.code(), row.primaryLanguage(), names,
                    row.displayOrder(), Merged.attributes(row, row.attributes(), null, rules, Target.REF_ITEM), marks,
                    !Boolean.FALSE.equals(row.active()), row.validFrom()), RefItem::id);
            report.write(new Write.OfRefItem(true, item));
            return Outcome.CREATED;
        }
        RefItem before = refItems.find(stored.get(), date).orElseThrow();
        ImportMatch.source(before.id(), null, row.source(), row.externalId());
        ImportMatch.fixed(row, before.id(), "kind", before.kind(), row.kind());
        ImportMatch.fixed(row, before.id(), "code", before.code(), row.code());
        String primaryLanguage = row.or("primaryLanguage", row.primaryLanguage(), before.names().primaryLanguage());
        Map<String, RefItemName> names = Merged.names(row, row.names(), before.names().byLanguage(),
                Merged.ITEM_NAMES);
        Integer displayOrder = row.or("displayOrder", row.displayOrder(), before.displayOrder());
        Map<String, Object> attributes = Merged.attributes(row, row.attributes(), before.attributes(), rules,
                Target.REF_ITEM);
        Boolean active = row.or("active", row.active(), before.active());
        RefItem wanted = new RefItem(before.id(), before.kind(), before.code(),
                RefItemName.check(primaryLanguage, names), displayOrder == null ? 0 : displayOrder, attributes,
                active == null || active,
                Boolean.TRUE.equals(row.or("legalEntity", row.legalEntity(), before.legalEntity())),
                Boolean.TRUE.equals(row.or("actingHead", row.actingHead(), before.actingHead())),
                Boolean.TRUE.equals(row.or("lateral", row.lateral(), before.lateral())),
                Boolean.TRUE.equals(row.or("rollup", row.rollup(), before.rollup())), before.version(),
                before.validFrom(), before.validTo());
        if (wanted.equals(before)) {
            return Outcome.UNCHANGED;
        }
        RefItem item = refItemService.update(before.id(), primaryLanguage, names, displayOrder, attributes, active,
                new RefItemService.Marks(wanted.legalEntity(), wanted.actingHead(), wanted.lateral(),
                        wanted.rollup()), date.toString());
        report.write(new Write.OfRefItem(false, item));
        return Outcome.CHANGED;
    }
}
