package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

final class MemoryOrgUnitStore implements OrgUnitStore {

    final Map<String, OrgUnit> units = new LinkedHashMap<>();
    final List<OrgUnitVersion> versions = new ArrayList<>();
    final List<OrgUnitLink> links = new ArrayList<>();

    @Override
    public String defaultHierarchyId() {
        return "default-hierarchy";
    }

    @Override
    public Optional<OrgUnit> findUnit(String id) {
        return Optional.ofNullable(units.get(id));
    }

    @Override
    public Optional<OrgUnit> findUnitByCode(String code) {
        return units.values().stream().filter(u -> code.equals(u.code())).findFirst();
    }

    @Override
    public void insertUnit(OrgUnit unit) {
        units.put(unit.id(), unit);
    }

    @Override
    public List<OrgUnitVersion> versions(String orgUnitId) {
        return versions.stream()
                .filter(v -> v.orgUnitId().equals(orgUnitId))
                .sorted(Comparator.comparing(v -> v.period().from()))
                .toList();
    }

    @Override
    public void insertVersion(OrgUnitVersion version) {
        versions.add(version);
    }

    @Override
    public void updateVersion(OrgUnitVersion version) {
        versions.replaceAll(v -> v.id().equals(version.id()) ? version : v);
    }

    @Override
    public List<OrgUnitLink> parentLinks(String hierarchyId, String childId) {
        return links.stream()
                .filter(l -> l.hierarchyId().equals(hierarchyId) && l.childId().equals(childId))
                .sorted(Comparator.comparing(l -> l.period().from()))
                .toList();
    }

    @Override
    public void insertLink(OrgUnitLink link) {
        links.add(link);
    }

    @Override
    public void updateLink(OrgUnitLink link) {
        links.replaceAll(l -> l.id().equals(link.id()) ? link : l);
    }

    @Override
    public void deleteLink(String id) {
        links.removeIf(l -> l.id().equals(id));
    }
}
