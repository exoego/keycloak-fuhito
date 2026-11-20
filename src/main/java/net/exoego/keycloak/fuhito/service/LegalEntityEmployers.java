package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Takes the employer from the legal entities of the realm, or from the external employers that the request names.
 * A request that names no employer gets the only legal entity of the date. Nothing is guessed.
 */
public class LegalEntityEmployers implements Employers {

    private static final OrgUnitStore.Search LEGAL_ENTITIES = new OrgUnitStore.Search(null, null, null, null, null,
            true, null);

    private final OrgUnitStore orgUnits;
    private final RefItemStore refItems;

    /**
     * Creates the rule.
     *
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     */
    public LegalEntityEmployers(OrgUnitStore orgUnits, RefItemStore refItems) {
        this.orgUnits = orgUnits;
        this.refItems = refItems;
    }

    @Override
    public Employer of(String requestedId, String externalEmployer, LocalDate firstDate) {
        if (externalEmployer == null) {
            return Employer.legalEntity(legalEntity(requestedId, firstDate));
        }
        if (requestedId != null) {
            throw FuhitoException.invalid("employment.employer_twice", Map.of("externalEmployer", externalEmployer));
        }
        RefItem item = refItems.findByCode(RefKind.EXTERNAL_EMPLOYER, externalEmployer)
                .orElseThrow(() -> FuhitoException.invalid("employment.external_employer_unknown",
                        Map.of("externalEmployer", externalEmployer)));
        if (!item.active()) {
            throw FuhitoException.invalid("employment.external_employer_inactive",
                    Map.of("externalEmployer", externalEmployer));
        }
        return Employer.external(item.id());
    }

    private String legalEntity(String requestedId, LocalDate firstDate) {
        String hierarchy = orgUnits.defaultHierarchyId();
        if (requestedId == null) {
            // Two are enough to know that there is more than one.
            List<PlacedOrgUnit> entities = orgUnits.searchOn(hierarchy, LEGAL_ENTITIES, firstDate, null, 0, 2);
            if (entities.isEmpty()) {
                throw FuhitoException.invalid("employment.no_legal_entity", Map.of("date", firstDate.toString()));
            }
            if (entities.size() > 1) {
                throw FuhitoException.invalid("employment.employer_required", Map.of("date", firstDate.toString()));
            }
            return entities.getFirst().unit().id();
        }
        PlacedOrgUnit unit = orgUnits.placedOn(hierarchy, List.of(requestedId), firstDate).stream().findFirst()
                .orElseThrow(() -> FuhitoException.invalid("employment.employer_unknown",
                        Map.of("employerId", requestedId, "date", firstDate.toString())));
        String unitTypeId = unit.version().unitTypeId();
        boolean legalEntity = unitTypeId != null && refItems.find(unitTypeId).map(RefItem::legalEntity).orElse(false);
        if (!legalEntity) {
            throw FuhitoException.invalid("employment.employer_not_legal_entity",
                    Map.of("employerId", requestedId, "name", unit.version().names().primary().name()));
        }
        return requestedId;
    }

    @Override
    public void requireCovers(Employer employer, ValidPeriod period) {
        if (employer.isExternal()) {
            return;
        }
        String employerId = employer.legalEntityId();
        LocalDate end = orgUnits.versions(employerId).getLast().period().to();
        if (end != null && (period.to() == null || period.to().isAfter(end))) {
            throw FuhitoException.conflict("employment.employer_ends",
                    Map.of("employerId", employerId, "date", end.toString()));
        }
    }
}
