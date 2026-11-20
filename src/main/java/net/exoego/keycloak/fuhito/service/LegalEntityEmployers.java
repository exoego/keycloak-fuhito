package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
        Optional<PlacedOrgUnit> unit = orgUnits.placedOn(hierarchy, List.of(requestedId), firstDate).stream()
                .findFirst();
        String unitTypeId = unit.map(placed -> placed.version().unitTypeId()).orElse(null);
        requireLegalEntity(requestedId, firstDate, unit.isPresent(),
                unitTypeId != null && refItems.find(unitTypeId).map(RefItem::legalEntity).orElse(false),
                () -> unit.orElseThrow().version().names().primary().name());
        return requestedId;
    }

    /**
     * Requires that an org unit exists on the first date of an employment and is a legal entity on it.
     *
     * @param employerId the ID of the org unit
     * @param firstDate the first date of the employment
     * @param exists true when the unit exists on the date
     * @param legalEntity true when the unit type of the unit on the date is marked as a legal entity
     * @param name gives the name of the unit
     * @throws FuhitoException when the unit does not exist on the date, or is not a legal entity
     */
    static void requireLegalEntity(String employerId, LocalDate firstDate, boolean exists, boolean legalEntity,
            Supplier<String> name) {
        if (!exists) {
            throw FuhitoException.invalid("employment.employer_unknown",
                    Map.of("employerId", employerId, "date", firstDate.toString()));
        }
        if (!legalEntity) {
            throw FuhitoException.invalid("employment.employer_not_legal_entity",
                    Map.of("employerId", employerId, "name", name.get()));
        }
    }

    @Override
    public void requireCovers(Employer employer, ValidPeriod period) {
        if (employer.isExternal()) {
            return;
        }
        String employerId = employer.legalEntityId();
        requireEndsBy(employerId, orgUnits.versions(employerId).getLast().period().to(), period);
    }

    /**
     * Requires that an employment ends by the end of its employer.
     *
     * @param employerId the ID of the employer
     * @param end the end of the employer, or null when it has none
     * @param period the period of the employment
     * @throws FuhitoException when the employer ends before the employment does
     */
    static void requireEndsBy(String employerId, LocalDate end, ValidPeriod period) {
        if (end != null && (period.to() == null || period.to().isAfter(end))) {
            throw FuhitoException.conflict("employment.employer_ends",
                    Map.of("employerId", employerId, "date", end.toString()));
        }
    }
}
