package net.exoego.keycloak.fuhito.rest;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.rest.UsersResource.MaskRepresentation;
import net.exoego.keycloak.fuhito.service.UserMaskingService;

/**
 * Masks and erases the personal data of one person, and records it.
 */
final class PersonData {

    private static final String RESOURCE_TYPE = "FUHITO_PERSON_DATA";

    private final RequestContext context;
    private final UserMaskingService masking;
    private final Consumer<String> erase;

    PersonData(RequestContext context, UserMaskingService masking, Consumer<String> erase) {
        this.context = context;
        this.masking = masking;
        this.erase = erase;
    }

    MaskRepresentation mask(Person person) {
        String id = person.id();
        var mask = new MaskRepresentation(id, person.userId(), masking.mask(id).toString());
        context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/people/" + id + "/mask", mask);
        return mask;
    }

    void erase(String personId) {
        erase.accept(personId);
        // The entry says that data was erased and by whom. It has nothing about the person but the ID.
        context.recordChange(Change.DELETE, RESOURCE_TYPE, personId, "fuhito/people/" + personId + "/data", null);
    }

    Optional<Instant> maskedAt(String personId) {
        return masking.maskedAt(personId);
    }
}
