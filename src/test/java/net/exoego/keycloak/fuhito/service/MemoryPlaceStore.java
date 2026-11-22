package net.exoego.keycloak.fuhito.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlaceSpan;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.PlaceStore;

final class MemoryPlaceStore implements PlaceStore {

    private final MemoryOrgUnitStore units;
    private final MemoryAssignmentStore assignments;

    /** The units whose versions a write counted up for a read that it changed, in order. */
    final List<String> countedUnits = new ArrayList<>();

    /** The assignments whose versions a write counted up for a read that it changed, in order. */
    final List<String> countedAssignments = new ArrayList<>();

    MemoryPlaceStore(MemoryOrgUnitStore units, MemoryAssignmentStore assignments) {
        this.units = units;
        this.assignments = assignments;
    }

    @Override
    public List<PlaceSpan> placesBelow(String orgUnitId, ValidPeriod period) {
        List<PlaceSpan> spans = new ArrayList<>();
        Deque<PlaceSpan> next = new ArrayDeque<>();
        versions(orgUnitId).forEach(v -> v.period().intersection(period).ifPresent(dates -> next.add(
                new PlaceSpan(orgUnitId, dates, v.workLocationId(), v.costCenterId()))));
        while (!next.isEmpty()) {
            PlaceSpan span = next.pop();
            spans.add(span);
            units.links.stream()
                    .filter(l -> l.hierarchyId().equals(units.defaultHierarchyId())
                            && l.parentId().equals(span.orgUnitId()))
                    .forEach(l -> l.period().intersection(span.period()).ifPresent(linked -> versions(l.childId())
                            .forEach(v -> v.period().intersection(linked).ifPresent(dates -> next.add(new PlaceSpan(
                                    l.childId(), dates,
                                    v.workLocationId() == null ? span.workLocationId() : v.workLocationId(),
                                    v.costCenterId() == null ? span.costCenterId() : v.costCenterId()))))));
        }
        return spans;
    }

    private List<OrgUnitVersion> versions(String orgUnitId) {
        return units.versions.stream().filter(v -> v.orgUnitId().equals(orgUnitId)).toList();
    }

    @Override
    public List<PlaceRow> rowsTakingPlace(Collection<String> orgUnitIds, boolean workLocation, ValidPeriod period) {
        return assignments.rows.stream()
                .filter(row -> orgUnitIds.contains(row.orgUnitId()) && row.period().overlaps(period))
                .filter(row -> (workLocation ? row.workLocationId() : row.costCenterId()) == null)
                .map(row -> new PlaceRow(row.assignmentId(), row.orgUnitId(), row.period()))
                .toList();
    }

    @Override
    public void countUpUnits(Collection<String> ids) {
        countedUnits.addAll(ids);
        ids.forEach(id -> units.units.computeIfPresent(id, (key, unit) -> new OrgUnit(unit.id(), unit.source(),
                unit.keycloakOrganizationId(), unit.timeZone(), unit.dayStart(), unit.version() + 1)));
    }

    @Override
    public void countUpAssignments(Collection<String> assignmentIds) {
        countedAssignments.addAll(assignmentIds);
    }
}
