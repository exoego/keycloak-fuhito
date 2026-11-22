package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Checks that the rows of an export keep the rules between rows, and between the fields of one row, that the writes
 * keep. For example, an assignment lies inside its employment, and an announced change order has the time of its
 * announcement. A restore checks them before it writes, so that a row that breaks one fails with an error that names
 * it. Each rule is the one that the writes use, so the two cannot differ. The export is the whole data of the realm,
 * because a restore writes into a realm with no data.
 */
final class RestoreRules {

    private final RestoreUnits units;
    private final Map<String, RealmExport.EmploymentRow> employments = new HashMap<>();
    private final Map<String, List<RealmExport.Period>> rows = new HashMap<>();

    private record StaffNumber(String employerId, String externalEmployerId, String number) {
    }

    private RestoreRules(RealmExport document, RestoreUnits units) {
        this.units = units;
        all(document.employments()).forEach(e -> employments.put(e.id(), e));
        for (RealmExport.AssignmentRows assignment : all(document.assignments())) {
            List<RealmExport.Period> sorted = new ArrayList<>(all(assignment.periods()));
            sorted.sort(Comparator.comparing(r -> LocalDate.parse(r.validFrom())));
            rows.put(assignment.assignmentId(), sorted);
        }
    }

    /**
     * Requires that the rows of an export keep the rules between rows of the writes.
     *
     * @param document the export, whose values are checked, whose references name rows of the export and items of
     *        the right kind, whose IDs and keys are unique, and whose rows that must not share a date do not
     * @throws FuhitoException when a row breaks a rule. The error is {@code restore.bad_value} and names the list, the
     *         ID of the row, the field, and the error of the write for the rule in {@code rule} when the write has one.
     */
    static void require(RealmExport document) {
        all(document.refItems()).forEach(RestoreRules::requireNames);
        RestoreUnits units = new RestoreUnits(document);
        units.require();
        RestoreRules rules = new RestoreRules(document, units);
        requireStaffNumbers(document);
        all(document.employments()).forEach(rules::requireEmployment);
        all(document.assignments()).forEach(rules::requireRows);
        all(document.assignments()).forEach(rules::requireAssignment);
        all(document.reportingLines()).forEach(rules::requireLine);
        requireOneSolid(document);
        all(document.unitRoles()).forEach(rules::requireRole);
        all(document.changeOrders()).forEach(RestoreRules::requireAnnouncement);
    }

    // Only the announcement sets the user who announced an order, and it sets the time too. An export gives no
    // username for a user of another realm, so a time may come alone.
    private static void requireAnnouncement(RealmExport.Order order) {
        if (order.announcedBy() != null && order.announcedAt() == null) {
            throw broken("change-orders", order.id(), "announcedAt", null);
        }
    }

    // An item has names on every date from its first names on. Names with no start hold from the start.
    private static void requireNames(RealmExport.Item item) {
        List<RealmExport.ItemNames> sorted = new ArrayList<>(all(item.versions()));
        if (sorted.isEmpty()) {
            throw broken("ref-items", item.id(), "versions", null);
        }
        sorted.sort(Comparator.comparing(n -> start(n.validFrom())));
        requireNoGaps("ref-item-names", sorted, RealmExport.ItemNames::id,
                n -> new ValidPeriod(start(n.validFrom()), date(n.validTo())));
        if (sorted.getLast().validTo() != null) {
            throw broken("ref-item-names", sorted.getLast().id(), "validTo", null);
        }
    }

    private static LocalDate start(String validFrom) {
        return validFrom == null ? LocalDate.MIN : LocalDate.parse(validFrom);
    }

    // The row is the later one in the export.
    private static void requireStaffNumbers(RealmExport document) {
        Map<StaffNumber, List<String>> holders = new HashMap<>();
        for (RealmExport.EmploymentRow e : all(document.employments())) {
            if (e.staffNumber() != null) {
                List<String> people = holders.computeIfAbsent(new StaffNumber(e.employerId(), e.externalEmployerId(),
                        e.staffNumber()), key -> new ArrayList<>());
                check("employments", e.id(), "staffNumber",
                        () -> EmploymentService.requireOwnStaffNumber(e.staffNumber(), e.personId(), people));
                people.add(e.personId());
            }
        }
    }

    private void requireEmployment(RealmExport.EmploymentRow e) {
        ValidPeriod period = period(e.validFrom(), e.validTo());
        check("employments", e.id(), "endReasonId", () -> EmploymentImport.requireEnd(e.validTo(), e.endReasonId()));
        if (e.employerId() != null) {
            Optional<RealmExport.Version> version = units.versionOn(e.employerId(), period.from());
            // The error names the row, so the name of the unit is not needed.
            check("employments", e.id(), version.isPresent() ? "employerId" : "validFrom",
                    () -> LegalEntityEmployers.requireLegalEntity(e.employerId(), period.from(), version.isPresent(),
                            version.map(v -> units.legalEntity(v.unitTypeId())).orElse(false), e::employerId));
            check("employments", e.id(), "validTo", () -> LegalEntityEmployers.requireEndsBy(e.employerId(),
                    units.life(e.employerId()).to(), period));
        }
        if (e.continuesEmploymentId() != null) {
            RealmExport.EmploymentRow previous = employments.get(e.continuesEmploymentId());
            check("employments", e.id(), "continuesEmploymentId",
                    () -> EmploymentService.requireSamePerson(previous.id(), previous.personId(), e.personId()));
            EmploymentService.stillHolds(previous.id(), period(previous.validFrom(), previous.validTo()),
                    period.from()).ifPresent(error -> {
                        throw broken("employments", previous.id(), "validTo", error.code()).causedBy(error);
                    });
        }
        List<RealmExport.Terms> terms = new ArrayList<>(all(e.terms()));
        if (terms.isEmpty()) {
            throw broken("employments", e.id(), "terms", null);
        }
        terms.sort(Comparator.comparing(t -> LocalDate.parse(t.validFrom())));
        // The terms cover the employment with no gaps.
        if (!period.from().equals(LocalDate.parse(terms.getFirst().validFrom()))) {
            throw broken("employment-terms", terms.getFirst().id(), "validFrom", null);
        }
        requireNoGaps("employment-terms", terms, RealmExport.Terms::id, t -> period(t.validFrom(), t.validTo()));
        if (!Objects.equals(period.to(), date(terms.getLast().validTo()))) {
            throw broken("employment-terms", terms.getLast().id(), "validTo", null);
        }
        for (RealmExport.Terms t : terms) {
            check("employment-terms", t.id(), "leaveReasonId",
                    () -> EmploymentService.requireLeave(EmploymentStatus.valueOf(t.status()), t.leaveReasonId()));
            check("employment-terms", t.id(), "contractEndDate", () -> EmploymentService.requireContractEnd(
                    date(t.contractEndDate()), LocalDate.parse(t.validFrom())));
        }
    }

    private void requireRows(RealmExport.AssignmentRows assignment) {
        List<RealmExport.Period> sorted = rows.get(assignment.assignmentId());
        if (sorted.isEmpty()) {
            throw broken("assignments", assignment.assignmentId(), "periods", null);
        }
        requireNoGaps("assignments", sorted, RealmExport.Period::rowId, r -> period(r.validFrom(), r.validTo()));
        // A write never changes the employment or the org unit of an assignment.
        RealmExport.Period first = sorted.getFirst();
        for (RealmExport.Period row : sorted) {
            if (!row.employmentId().equals(first.employmentId())) {
                throw broken("assignments", row.rowId(), "employmentId", null);
            }
            if (!row.orgUnitId().equals(first.orgUnitId())) {
                throw broken("assignments", row.rowId(), "orgUnitId", null);
            }
        }
    }

    private void requireAssignment(RealmExport.AssignmentRows assignment) {
        for (RealmExport.Period row : rows.get(assignment.assignmentId())) {
            ValidPeriod period = period(row.validFrom(), row.validTo());
            RealmExport.EmploymentRow e = employments.get(row.employmentId());
            ValidPeriod employment = period(e.validFrom(), e.validTo());
            check("assignments", row.rowId(), outside(period, employment),
                    () -> AssignmentService.requireInsideEmployment(e.id(), employment, period));
            ValidPeriod life = units.life(row.orgUnitId());
            check("assignments", row.rowId(), outside(period, life),
                    () -> AssignmentService.requireOrgUnitCovers(row.orgUnitId(), period, Optional.of(life)));
        }
    }

    private void requireLine(RealmExport.Line line) {
        ValidPeriod period = period(line.validFrom(), line.validTo());
        List<RealmExport.Period> sorted = rows.get(line.assignmentId());
        ValidPeriod life = period(sorted.getFirst().validFrom(), sorted.getLast().validTo());
        check("reporting-lines", line.id(), outside(period, life),
                () -> ReportingLineService.requireInsideAssignment(line.assignmentId(), life, period));
        String personId = employments.get(sorted.getFirst().employmentId()).personId();
        check("reporting-lines", line.id(), "managerPersonId",
                () -> ReportingLineRules.requireNotSelf(line.managerPersonId(), personId));
    }

    // Of two solid lines that share a date, the one that starts later breaks the rule.
    private static void requireOneSolid(RealmExport document) {
        List<RealmExport.Line> sorted = new ArrayList<>(all(document.reportingLines()));
        sorted.sort(Comparator.comparing(l -> LocalDate.parse(l.validFrom())));
        Map<String, List<ReportingLine>> earlier = new HashMap<>();
        for (RealmExport.Line line : sorted) {
            ReportingLine.Kind kind = ReportingLine.Kind.valueOf(line.kind());
            ValidPeriod period = period(line.validFrom(), line.validTo());
            List<ReportingLine> others = earlier.computeIfAbsent(line.assignmentId(), id -> new ArrayList<>());
            if (kind == ReportingLine.Kind.SOLID) {
                check("reporting-lines", line.id(), "kind",
                        () -> ReportingLineRules.requireNoOtherSolid(line.assignmentId(), period, others));
            }
            others.add(new ReportingLine(line.id(), line.assignmentId(), line.managerPersonId(), null, kind, period,
                    1));
        }
    }

    private void requireRole(RealmExport.Role role) {
        ValidPeriod period = period(role.validFrom(), role.validTo());
        RealmExport.EmploymentRow e = employments.get(role.employmentId());
        ValidPeriod employment = period(e.validFrom(), e.validTo());
        check("unit-roles", role.id(), outside(period, employment),
                () -> UnitRoleService.requireInsideEmployment(e.id(), employment, period));
        ValidPeriod life = units.life(role.orgUnitId());
        check("unit-roles", role.id(), outside(period, life),
                () -> UnitRoleService.requireInsideOrgUnit(role.orgUnitId(), life, period));
    }

    /**
     * Runs the rule of a write on a row, and gives its error the row.
     *
     * @param list the list of the row
     * @param id the ID of the row
     * @param field the field that breaks the rule when it fails
     * @param rule the rule
     * @throws FuhitoException when the rule fails, as {@link #broken}
     */
    static void check(String list, String id, String field, Runnable rule) {
        try {
            rule.run();
        } catch (FuhitoException e) {
            throw broken(list, id, field, e.code()).causedBy(e);
        }
    }

    /**
     * Returns the error of a row that breaks a rule.
     *
     * @param list the list of the row
     * @param id the ID of the row
     * @param field the field that breaks the rule
     * @param rule the error of the write for the rule, or null when the write has none
     * @return the error
     */
    static FuhitoException broken(String list, String id, String field, String rule) {
        Map<String, String> params = new HashMap<>(Map.of("list", list, "id", id, "field", field));
        if (rule != null) {
            params.put("rule", rule);
        }
        return FuhitoException.invalid("restore.bad_value", params);
    }

    /**
     * Requires that each row of a sorted list starts where the row before it ends.
     *
     * @param <T> the type of the rows
     * @param list the list of the rows
     * @param sorted the rows, which do not overlap, by their starts
     * @param id gives the ID of a row
     * @param period gives the period of a row
     * @throws FuhitoException when a row starts after the end of the row before it
     */
    static <T> void requireNoGaps(String list, List<T> sorted, Function<T, String> id,
            Function<T, ValidPeriod> period) {
        for (int i = 1; i < sorted.size(); i++) {
            if (!period.apply(sorted.get(i - 1)).to().equals(period.apply(sorted.get(i)).from())) {
                throw broken(list, id.apply(sorted.get(i)), "validFrom", null);
            }
        }
    }

    /**
     * Names the date of a row that lies outside another period: its start when it starts before the period, or else
     * its end.
     *
     * @param row the period of the row
     * @param around the period that the row must lie inside
     * @return {@code validFrom} or {@code validTo}
     */
    static String outside(ValidPeriod row, ValidPeriod around) {
        return row.from().isBefore(around.from()) ? "validFrom" : "validTo";
    }

    static ValidPeriod period(String from, String to) {
        return new ValidPeriod(LocalDate.parse(from), date(to));
    }

    static LocalDate date(String text) {
        return text == null ? null : LocalDate.parse(text);
    }

    static <T> List<T> all(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
