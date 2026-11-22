package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.MemberCursor;
import net.exoego.keycloak.fuhito.domain.MemberFilter;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;

final class MemoryAssignmentStore implements AssignmentStore {

    final List<Assignment> rows = new ArrayList<>();

    /** The IDs of the assignments in each call of {@link #countUp}. */
    final List<List<String>> countedUp = new ArrayList<>();

    /** The stores that a list of members or of users reads besides the rows. A test that reads those sets them. */
    MemoryOrgUnitStore orgUnits;
    MemoryEmploymentStore employments;

    @Override
    public long countMembersOn(MemberFilter filter) {
        return membersOn(filter, null, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public List<Assignment> membersOn(MemberFilter filter, MemberCursor after, int first, int max) {
        List<String> unitIds = filter.includeDescendants()
                ? orgUnits.treeOn(filter.hierarchyId(), filter.orgUnitId(), null, filter.date(), 0,
                        Integer.MAX_VALUE)
                        .stream().map(placed -> placed.unit().id()).toList()
                : List.of(filter.orgUnitId());
        Comparator<Assignment> order = Comparator
                .comparingInt((Assignment row) -> unitIds.indexOf(row.orgUnitId()))
                .thenComparingInt(Assignment::displayOrder)
                .thenComparing(row -> row.period().from())
                .thenComparing(Assignment::assignmentId);
        return rowsOn(unitIds, filter.date()).stream()
                .filter(row -> filter.occupationId() == null || filter.occupationId().equals(row.occupationId()))
                .filter(row -> filter.jobTitleId() == null || filter.jobTitleId().equals(row.jobTitleId()))
                .filter(row -> filter.status() == null
                        || employments.termsOn(List.of(row.employmentId()), filter.date()).stream()
                                .anyMatch(terms -> terms.status() == filter.status()))
                .filter(row -> filter.seconded() == null || filter.seconded().wanted() == seconded(row, filter))
                .filter(row -> filter.places() == null || placed(row, filter))
                .sorted(order)
                .filter(row -> after == null || order.compare(row, new Assignment(null, after.assignmentId(), null,
                        after.orgUnitId(), ValidPeriod.startingAt(after.validFrom()), null, null, null, null, null,
                        false, false, null, after.displayOrder(), Map.of(), null, 1)) > 0)
                .skip(first)
                .limit(max)
                .toList();
    }

    private boolean seconded(Assignment row, MemberFilter filter) {
        return employments.findAll(List.of(row.employmentId())).getFirst().secondedTo(
                orgUnits.legalEntitiesOn(List.of(row.orgUnitId()), filter.date()).get(row.orgUnitId()));
    }

    private boolean placed(Assignment row, MemberFilter filter) {
        UnitDefaults unit = orgUnits.defaultsOn(List.of(row.orgUnitId()), filter.date())
                .getOrDefault(row.orgUnitId(), UnitDefaults.NONE);
        String workLocationId = row.workLocationId() == null ? unit.workLocationId() : row.workLocationId();
        String costCenterId = row.costCenterId() == null ? unit.costCenterId() : row.costCenterId();
        MemberFilter.Places wanted = filter.places();
        return (wanted.workLocationId() == null || wanted.workLocationId().equals(workLocationId))
                && (wanted.costCenterId() == null || wanted.costCenterId().equals(costCenterId));
    }

    @Override
    public List<Assignment> rows(String assignmentId) {
        return rows.stream()
                .filter(r -> r.assignmentId().equals(assignmentId))
                .sorted(Comparator.comparing(r -> r.period().from()))
                .toList();
    }

    @Override
    public List<Assignment> rowsOf(Collection<String> assignmentIds) {
        return rows.stream()
                .filter(r -> assignmentIds.contains(r.assignmentId()))
                .sorted(Comparator.comparing(Assignment::assignmentId).thenComparing(r -> r.period().from()))
                .toList();
    }

    @Override
    public List<Assignment> rowsOfEmployment(String employmentId) {
        return rows.stream()
                .filter(r -> r.employmentId().equals(employmentId))
                .sorted(Comparator.comparing(r -> r.period().from()))
                .toList();
    }

    @Override
    public Map<String, List<Assignment>> endedOnStart(Collection<String> assignmentIds) {
        Map<String, List<Assignment>> found = new HashMap<>();
        for (String id : assignmentIds) {
            List<Assignment> own = rows(id);
            if (own.isEmpty()) {
                continue;
            }
            Assignment first = own.getFirst();
            List<Assignment> ended = rows.stream()
                    .map(Assignment::assignmentId)
                    .distinct()
                    .map(other -> rows(other).getLast())
                    .filter(last -> personOf(last).equals(personOf(first))
                            && first.period().from().equals(last.period().to()) && last.primary() == first.primary())
                    .sorted(Comparator.comparing(Assignment::assignmentId))
                    .toList();
            if (!ended.isEmpty()) {
                found.put(id, ended);
            }
        }
        return found;
    }

    @Override
    public Map<String, List<Assignment>> firstRowsOfPeople(Collection<String> employmentIds) {
        Map<String, List<Assignment>> found = new HashMap<>();
        List<Assignment> firsts = rows.stream()
                .map(Assignment::assignmentId)
                .distinct()
                .sorted()
                .map(id -> rows(id).getFirst())
                .toList();
        for (String employmentId : employmentIds) {
            String person = personOf(employmentId);
            List<Assignment> ofPerson = firsts.stream().filter(first -> personOf(first).equals(person)).toList();
            if (!ofPerson.isEmpty()) {
                found.put(employmentId, ofPerson);
            }
        }
        return found;
    }

    @Override
    public void countUp(Collection<String> assignmentIds) {
        countedUp.add(List.copyOf(assignmentIds));
        rows.replaceAll(r -> assignmentIds.contains(r.assignmentId()) ? new Assignment(r.rowId(), r.assignmentId(),
                r.employmentId(), r.orgUnitId(), r.period(), r.jobTitleId(), r.occupationId(), r.kindId(),
                r.workLocationId(), r.costCenterId(), r.head(), r.primary(), r.allocationPercent(),
                r.displayOrder(), r.attributes(), r.source(), r.version() + 1) : r);
    }

    // A test that sets no store of employments has one person for each employment.
    private String personOf(Assignment row) {
        return personOf(row.employmentId());
    }

    private String personOf(String employmentId) {
        return employments == null ? employmentId
                : employments.find(employmentId).map(Employment::personId).orElse(employmentId);
    }

    @Override
    public long assignmentsHoldingFrom(String orgUnitId, LocalDate date) {
        return rows.stream()
                .filter(r -> r.orgUnitId().equals(orgUnitId) && r.period().endsAfter(date))
                .map(Assignment::assignmentId)
                .distinct()
                .count();
    }

    @Override
    public List<Assignment> rowsOn(Collection<String> orgUnitIds, LocalDate date) {
        return rows.stream().filter(r -> orgUnitIds.contains(r.orgUnitId()) && r.period().contains(date)).toList();
    }

    @Override
    public Map<String, Set<String>> usersOn(Collection<String> orgUnitIds, LocalDate date) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Assignment row : rowsOn(orgUnitIds, date)) {
            employments.find(row.employmentId()).map(Employment::userId).ifPresent(userId -> result
                    .computeIfAbsent(row.orgUnitId(), id -> new LinkedHashSet<>()).add(userId));
        }
        return result;
    }

    @Override
    public List<Assignment> rowsBySourceOn(String source, LocalDate date) {
        return rows.stream().filter(r -> source.equals(r.source()) && r.period().contains(date)).toList();
    }

    @Override
    public void insertAll(List<Assignment> added) {
        rows.addAll(added);
    }

    @Override
    public void updateAll(List<Assignment> changed) {
        changed.forEach(row -> rows.replaceAll(r -> r.rowId().equals(row.rowId()) ? new Assignment(row.rowId(),
                row.assignmentId(), row.employmentId(), row.orgUnitId(), row.period(), row.jobTitleId(),
                row.occupationId(), row.kindId(), row.workLocationId(), row.costCenterId(), row.head(),
                row.primary(), row.allocationPercent(), row.displayOrder(), row.attributes(), row.source(),
                r.version()) : r));
    }

    @Override
    public void deleteAll(Collection<String> rowIds) {
        rows.removeIf(r -> rowIds.contains(r.rowId()));
    }

    @Override
    public List<Assignment> searchOn(Search search, String afterAssignmentId, int first, int max) {
        return rows.stream()
                .filter(row -> row.period().contains(search.date()))
                .filter(row -> search.orgUnitId() == null || search.orgUnitId().equals(row.orgUnitId()))
                .filter(row -> search.jobTitleId() == null || search.jobTitleId().equals(row.jobTitleId()))
                .filter(row -> search.occupationId() == null || search.occupationId().equals(row.occupationId()))
                .filter(row -> search.kindId() == null || search.kindId().equals(row.kindId()))
                .filter(row -> afterAssignmentId == null || row.assignmentId().compareTo(afterAssignmentId) > 0)
                .sorted(Comparator.comparing(Assignment::assignmentId))
                .skip(first).limit(max).toList();
    }

    @Override
    public long countSearchOn(Search search) {
        return searchOn(search, null, 0, Integer.MAX_VALUE).size();
    }
}
