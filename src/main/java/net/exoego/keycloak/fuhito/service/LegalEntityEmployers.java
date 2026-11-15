package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Takes the employer from the legal entities of the realm.
 * A request that names no employer gets the only legal entity of the date. Nothing is guessed.
 */
public class LegalEntityEmployers implements Employers {

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
    public String of(String requestedId, LocalDate firstDate) {
        String hierarchy = orgUnits.defaultHierarchyId();
        if (requestedId == null) {
            // Two are enough to know that there is more than one.
            List<PlacedOrgUnit> entities = orgUnits.legalEntitiesOn(hierarchy, firstDate, 0, 2);
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
                    Map.of("employerId", requestedId, "name", unit.version().name()));
        }
        return requestedId;
    }

    @Override
    public void requireCovers(String employerId, ValidPeriod period) {
        LocalDate end = orgUnits.versions(employerId).getLast().period().to();
        if (end != null && (period.to() == null || period.to().isAfter(end))) {
            throw FuhitoException.conflict("employment.employer_ends",
                    Map.of("employerId", employerId, "date", end.toString()));
        }
    }
}
