package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.store.EmploymentStore;

final class MemoryEmploymentStore implements EmploymentStore {

    final Map<String, Employment> employments = new LinkedHashMap<>();
    final List<EmploymentTerms> terms = new ArrayList<>();

    @Override
    public Optional<Employment> find(String id) {
        return Optional.ofNullable(employments.get(id));
    }

    @Override
    public Optional<Employment> findByExternalId(String source, String externalId) {
        return employments.values().stream()
                .filter(e -> source.equals(e.source()) && externalId.equals(e.externalId()))
                .findFirst();
    }

    @Override
    public List<Employment> findByStaffNumber(String staffNumber) {
        return employments.values().stream().filter(e -> staffNumber.equals(e.staffNumber())).toList();
    }

    @Override
    public List<Employment> findByUser(String userId) {
        return employments.values().stream()
                .filter(e -> e.userId().equals(userId))
                .sorted(Comparator.comparing(e -> e.period().from()))
                .toList();
    }

    @Override
    public List<Employment> findAll(Collection<String> ids) {
        return employments.values().stream().filter(e -> ids.contains(e.id())).toList();
    }

    @Override
    public List<EmploymentTerms> termsOn(Collection<String> employmentIds, LocalDate date) {
        return terms.stream()
                .filter(t -> employmentIds.contains(t.employmentId()) && t.period().contains(date))
                .toList();
    }

    @Override
    public void insert(Employment employment) {
        employments.put(employment.id(), employment);
    }

    @Override
    public List<EmploymentTerms> terms(String employmentId) {
        return terms.stream()
                .filter(t -> t.employmentId().equals(employmentId))
                .sorted(Comparator.comparing(t -> t.period().from()))
                .toList();
    }

    @Override
    public void insertTerms(EmploymentTerms terms) {
        this.terms.add(terms);
    }

    @Override
    public void updateTerms(EmploymentTerms changed) {
        terms.replaceAll(t -> t.id().equals(changed.id()) ? changed : t);
    }

    @Override
    public void update(Employment employment) {
        employments.put(employment.id(), employment);
    }

    @Override
    public void deleteTerms(String termsId) {
        terms.removeIf(t -> t.id().equals(termsId));
    }
}
