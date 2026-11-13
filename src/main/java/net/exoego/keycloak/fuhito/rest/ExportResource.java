package net.exoego.keycloak.fuhito.rest;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Supplier;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.ChangeOrderService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.OrgUnitHistory;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.RefItemService;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * The export of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
public class ExportResource {

    /**
     * What the export reads from.
     *
     * @param refItems the operations on reference items of the realm
     * @param orgUnits the operations on org units of the realm
     * @param orgUnitStore the storage of org units of the realm
     * @param employments the operations on employments of the realm
     * @param employmentStore the storage of employments of the realm
     * @param assignments the operations on assignments of the realm
     * @param assignmentStore the storage of assignments of the realm
     * @param changeOrders the operations on change orders of the realm
     */
    public record Sources(RefItemService refItems, OrgUnitService orgUnits, OrgUnitStore orgUnitStore,
            EmploymentService employments, EmploymentStore employmentStore, AssignmentService assignments,
            AssignmentStore assignmentStore, ChangeOrderService changeOrders) {
    }

    private final RequestContext context;
    private final Supplier<Sources> sources;
    private final Clock clock;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param sources what the export reads from
     * @param clock the clock for the time of the export
     */
    public ExportResource(RequestContext context, Supplier<Sources> sources, Clock clock) {
        this.context = context;
        this.sources = sources;
        this.clock = clock;
    }

    /**
     * Returns all organization data of the realm, with every period of every resource, ended ones included.
     *
     * @return the export
     */
    @GET
    public ExportRepresentation export() {
        context.requireRead();
        return Calls.run(context, () -> {
            Sources from = sources.get();
            return new ExportRepresentation(
                    Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString(),
                    Arrays.stream(RefKind.values())
                            .flatMap(kind -> from.refItems().list(kind, true).stream())
                            .map(RefItemRepresentation::of)
                            .toList(),
                    from.orgUnitStore().units().stream()
                            .map(unit -> from.orgUnits().history(unit.id()))
                            .sorted(Comparator
                                    .comparing((OrgUnitHistory h) -> h.versions().get(0).version().period().from())
                                    .thenComparing(h -> h.unit().id()))
                            .map(OrgUnitHistoryRepresentation::of)
                            .toList(),
                    from.employmentStore().all().stream()
                            .map(employment -> from.employments().get(employment.id()))
                            .map(EmploymentRepresentation::of)
                            .toList(),
                    from.assignmentStore().allAssignmentIds().stream()
                            .map(from.assignments()::get)
                            .map(AssignmentRepresentation::of)
                            .toList(),
                    from.changeOrders().list(null, null).stream().map(ChangeOrderRepresentation::of).toList());
        });
    }
}
