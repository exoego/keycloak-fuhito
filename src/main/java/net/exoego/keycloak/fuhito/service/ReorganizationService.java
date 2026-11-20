package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Changes many org units on the same date.
 * The caller runs it in one transaction, so that a failure of one step undoes all steps.
 */
public class ReorganizationService {

    /**
     * What one step did.
     *
     * @param op the operation of the step
     * @param unit the unit after the step. For an end and a succession, it is the unit on its last date.
     * @param successions for a succession, the units before and after the unit that ended. Null otherwise.
     */
    public record Result(String op, OrgUnitState unit, OrgUnitSuccessions successions) {

        /**
         * Makes the result of a step that is not a succession.
         *
         * @param op the operation of the step
         * @param unit the unit after the step
         */
        public Result(String op, OrgUnitState unit) {
            this(op, unit, null);
        }
    }

    private static final Set<String> CHANGES = Set.of("change", "move", "end", "succeed");

    // A step that gives a field that its operation does not read fails, so that no value is dropped with no word.
    private static final Map<String, Set<String>> READS = Map.of(
            "create", Set.of("ref", "parentId", "parentRef", "code", "primaryLanguage", "names", "unitType",
                    "workLocation", "costCenter", "displayOrder", "attributes"),
            "change", Set.of("id", "unitRef", "version", "code", "primaryLanguage", "names", "unitType",
                    "workLocation", "costCenter", "displayOrder", "attributes"),
            "move", Set.of("id", "unitRef", "version", "parentId", "parentRef"),
            "end", Set.of("id", "unitRef", "version"),
            "succeed", Set.of("id", "unitRef", "version", "successorIds", "successorRefs"));

    private final OrgUnitService orgUnits;

    /**
     * Creates the service.
     *
     * @param orgUnits the operations on the org units of the realm
     */
    public ReorganizationService(OrgUnitService orgUnits) {
        this.orgUnits = orgUnits;
    }

    /**
     * Applies the steps in the order given, all on the effective date.
     * A later step can name a unit that an earlier create step made, by the {@code ref} of that step.
     * A step that changes a stored unit names the version of the unit that the caller read.
     * An empty text, a text of only spaces, or an empty list in a step fails before any step runs. So does a
     * field that the operation of the step does not read, a version of a step with no {@code id}, and a step that
     * names its unit or its parent both by ID and by {@code ref}.
     *
     * @param effectiveDate the date of every step, as {@code YYYY-MM-DD}
     * @param steps the steps
     * @param lock locks the stored units until the end of the transaction, before their versions are read
     * @return what each step did, in the same order
     * @throws FuhitoException when a step fails, has an empty value or a field that it does not read, names a unit
     *         in two ways, or names no version or an old one. Its values hold the position of the step, counted
     *         from 0, under the key "step".
     */
    public List<Result> apply(String effectiveDate, List<ReorganizationStep> steps,
            Consumer<Collection<String>> lock) {
        Checks.date("effectiveDate", Checks.optionalText("effectiveDate", effectiveDate));
        Checks.optionalList("steps", Checks.required("steps", steps));
        Map<Integer, ReorganizationStep> stored = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            ReorganizationStep step = steps.get(i);
            if (step != null) {
                atStep(i, () -> namedOnce(requireRead(EmptyValues.withoutEmptyValues(step))));
            }
            if (step != null && step.op() != null && CHANGES.contains(step.op()) && step.unitRef() == null
                    && step.id() != null) {
                stored.put(i, step);
            }
        }
        // All units are locked at once, so that two reorganizations of the same units lock them in the same order.
        lock.accept(stored.values().stream().map(ReorganizationStep::id).distinct().toList());
        // Every version is checked before the first step counts one up.
        stored.forEach((i, step) -> atStep(i, () -> {
            Checks.version(step.version(), orgUnits.version(step.id()));
            return step;
        }));
        Map<String, String> created = new HashMap<>();
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ReorganizationStep step = steps.get(i);
            results.add(atStep(i, () -> apply(effectiveDate, step, created)));
        }
        return results;
    }

    // A step with no operation or an unknown one fails later, with the reason of that.
    private static ReorganizationStep requireRead(ReorganizationStep step) {
        Set<String> reads = step.op() == null ? null : READS.get(step.op());
        if (reads == null) {
            return step;
        }
        for (RecordComponent component : ReorganizationStep.class.getRecordComponents()) {
            String field = component.getName();
            // Only a step with an ID names a stored unit, which has a version.
            boolean unread = !field.equals("op")
                    && (!reads.contains(field) || (field.equals("version") && step.id() == null));
            if (unread && EmptyValues.valueOf(component, step) != null) {
                throw FuhitoException.invalid("reorganization.field_unused", Map.of("field", field, "op", step.op()));
            }
        }
        return step;
    }

    private static ReorganizationStep namedOnce(ReorganizationStep step) {
        Checks.notBoth("id", step.id(), "unitRef", step.unitRef());
        Checks.notBoth("parentId", step.parentId(), "parentRef", step.parentRef());
        return step;
    }

    private static <T> T atStep(int index, Supplier<T> work) {
        try {
            return work.get();
        } catch (FuhitoException e) {
            Map<String, String> params = new HashMap<>(e.params());
            params.put("step", Integer.toString(index));
            throw new FuhitoException(e.reason(), e.code(), params).causedBy(e);
        }
    }

    private Result apply(String date, ReorganizationStep step, Map<String, String> created) {
        Checks.required("step", step);
        String op = Checks.required("op", step.op());
        return switch (op) {
            case "create" -> {
                OrgUnitState unit = orgUnits.create(new NewOrgUnit(step.code(), step.primaryLanguage(),
                        step.names(), step.unitType(), step.workLocation(), step.costCenter(),
                        resolve("parent", step.parentId(), step.parentRef(), created), date, step.displayOrder(),
                        step.attributes(), null, null));
                if (step.ref() != null && created.put(step.ref(), unit.unit().id()) != null) {
                    throw FuhitoException.invalid("reorganization.ref_taken", Map.of("ref", step.ref()));
                }
                yield new Result(op, unit);
            }
            case "change" -> new Result(op, orgUnits.changeDetails(target(step, created), new OrgUnitChange(date,
                    step.code(), step.primaryLanguage(), step.names(), step.unitType(), step.workLocation(),
                    step.costCenter(), step.displayOrder(), step.attributes())));
            case "move" -> new Result(op, orgUnits.move(target(step, created),
                    resolve("parent", step.parentId(), step.parentRef(), created), date));
            case "end" -> new Result(op, orgUnits.end(target(step, created), date));
            case "succeed" -> {
                String id = target(step, created);
                List<String> successors = new ArrayList<>();
                if (step.successorIds() != null) {
                    successors.addAll(step.successorIds());
                }
                if (step.successorRefs() != null) {
                    step.successorRefs().forEach(ref -> successors.add(resolve("successor", null, ref, created)));
                }
                OrgUnitState ended = orgUnits.succeed(id, date, successors);
                yield new Result(op, ended, orgUnits.successions(id));
            }
            default -> throw FuhitoException.invalid("invalid.choice", Map.of("field", "op", "value", op));
        };
    }

    private static String target(ReorganizationStep step, Map<String, String> created) {
        String id = resolve("unit", step.id(), step.unitRef(), created);
        if (id == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "id"));
        }
        return id;
    }

    private static String resolve(String what, String id, String ref, Map<String, String> created) {
        if (ref == null) {
            return id;
        }
        String resolved = created.get(ref);
        if (resolved == null) {
            throw FuhitoException.invalid("reorganization.ref_unknown", Map.of("field", what + "Ref", "ref", ref));
        }
        return resolved;
    }
}
