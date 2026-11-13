package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

final class MemoryReportingLineStore implements ReportingLineStore {

    final List<ReportingLine> lines = new ArrayList<>();

    @Override
    public Optional<ReportingLine> find(String id) {
        return lines.stream().filter(l -> l.id().equals(id)).findFirst();
    }

    @Override
    public List<ReportingLine> findByAssignment(String assignmentId) {
        return lines.stream().filter(l -> l.assignmentId().equals(assignmentId))
                .sorted(Comparator.comparing(l -> l.period().from())).toList();
    }

    @Override
    public List<ReportingLine> findByManager(String managerUserId) {
        return lines.stream().filter(l -> l.managerUserId().equals(managerUserId))
                .sorted(Comparator.comparing(l -> l.period().from())).toList();
    }

    @Override
    public void insert(ReportingLine line) {
        lines.add(line);
    }

    @Override
    public void update(ReportingLine line) {
        lines.replaceAll(l -> l.id().equals(line.id()) ? line : l);
    }

    @Override
    public void delete(String id) {
        lines.removeIf(l -> l.id().equals(id));
    }
}
