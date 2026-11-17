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
                .filter(e -> userId.equals(e.userId()))
                .sorted(Comparator.comparing(e -> e.period().from()))
                .toList();
    }

    @Override
    public List<Employment> findByEmployer(String employerId) {
        return employments.values().stream().filter(e -> employerId.equals(e.employerId())).toList();
    }

    @Override
    public List<Employment> findByPerson(String personId) {
        return employments.values().stream().filter(e -> e.personId().equals(personId)).toList();
    }

    @Override
    public List<Employment> findByUsers(Collection<String> userIds) {
        return employments.values().stream()
                .filter(e -> userIds.contains(e.userId()))
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
    public void delete(String id) {
        employments.remove(id);
    }

    @Override
    public void deleteTerms(String termsId) {
        terms.removeIf(t -> t.id().equals(termsId));
    }

    @Override
    public List<Employment> search(Search search, String afterId, int first, int max) {
        return employments.values().stream()
                .filter(e -> search.staffNumber() == null || search.staffNumber().equals(e.staffNumber()))
                .filter(e -> search.employerId() == null || search.employerId().equals(e.employerId()))
                .filter(e -> search.date() == null || e.period().contains(search.date()))
                .filter(e -> !search.byContractEnd() || terms.stream()
                        .filter(t -> t.employmentId().equals(e.id()) && t.period().contains(search.termsDate()))
                        .map(EmploymentTerms::contractEndDate)
                        .anyMatch(end -> end != null
                                && (search.contractEndFrom() == null || !end.isBefore(search.contractEndFrom()))
                                && (search.contractEndTo() == null || end.isBefore(search.contractEndTo()))))
                .filter(e -> afterId == null || e.id().compareTo(afterId) > 0)
                .sorted(Comparator.comparing(Employment::id))
                .skip(first).limit(max).toList();
    }

    @Override
    public long count(Search search) {
        return search(search, null, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public List<EmploymentTerms> termsOf(Collection<String> employmentIds) {
        return terms.stream().filter(t -> employmentIds.contains(t.employmentId()))
                .sorted(Comparator.comparing(EmploymentTerms::employmentId)
                        .thenComparing(t -> t.period().from()))
                .toList();
    }
}
