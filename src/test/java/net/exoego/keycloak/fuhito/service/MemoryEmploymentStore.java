package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
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
    public List<Employment> findByStaffNumber(String staffNumber) {
        return employments.values().stream().filter(e -> staffNumber.equals(e.staffNumber())).toList();
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
}
