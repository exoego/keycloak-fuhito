package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * @param unit the unit after the step. For an end, it is the unit on its last date.
     */
    public record Result(String op, OrgUnitState unit) {
    }

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
     *
     * @param effectiveDate the date of every step, as {@code YYYY-MM-DD}
     * @param steps the steps
     * @return what each step did, in the same order
     * @throws FuhitoException when a step fails. Its values have {@code step} with the position of the step,
     *         counted from 0.
     */
    public List<Result> apply(String effectiveDate, List<ReorganizationStep> steps) {
        Checks.date("effectiveDate", effectiveDate);
        Checks.required("steps", steps);
        if (steps.isEmpty()) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "steps"));
        }
        Map<String, String> created = new HashMap<>();
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            try {
                results.add(apply(effectiveDate, steps.get(i), created));
            } catch (FuhitoException e) {
                Map<String, String> params = new HashMap<>(e.params());
                params.put("step", Integer.toString(i));
                throw new FuhitoException(e.reason(), e.code(), params).causedBy(e);
            }
        }
        return results;
    }

    private Result apply(String date, ReorganizationStep step, Map<String, String> created) {
        Checks.required("step", step);
        String op = Checks.required("op", step.op());
        return switch (op) {
            case "create" -> {
                OrgUnitState unit = orgUnits.create(new NewOrgUnit(step.code(), step.name(), step.names(),
                        step.shortName(), step.shortNames(), step.phoneticName(), step.unitType(),
                        resolve("parent", step.parentId(), step.parentRef(), created), date, step.displayOrder(),
                        step.attributes(), null, null));
                if (step.ref() != null && created.put(step.ref(), unit.unit().id()) != null) {
                    throw FuhitoException.invalid("reorganization.ref_taken", Map.of("ref", step.ref()));
                }
                yield new Result(op, unit);
            }
            case "change" -> new Result(op, orgUnits.changeDetails(target(step, created), new OrgUnitChange(date,
                    step.name(), step.names(), step.shortName(), step.shortNames(), step.phoneticName(),
                    step.unitType(), step.displayOrder(), step.attributes())));
            case "move" -> new Result(op, orgUnits.move(target(step, created),
                    resolve("parent", step.parentId(), step.parentRef(), created), date));
            case "end" -> new Result(op, orgUnits.end(target(step, created), date));
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
