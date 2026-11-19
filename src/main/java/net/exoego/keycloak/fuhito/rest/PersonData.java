package net.exoego.keycloak.fuhito.rest;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.rest.UsersResource.MaskRepresentation;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.UserMaskingService;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import net.exoego.keycloak.fuhito.store.WriteScopes;

/**
 * Masks and erases the personal data of one person, and records it.
 */
final class PersonData {

    private static final String RESOURCE_TYPE = "FUHITO_PERSON_DATA";

    private final RequestContext context;
    private final UserMaskingService masking;
    private final UserDirectory users;
    private final Consumer<String> erase;
    private final PersonService people;
    private final WriteScopes scopes;

    PersonData(RequestContext context, UserMaskingService masking, UserDirectory users, Consumer<String> erase,
            PersonService people, WriteScopes scopes) {
        this.scopes = scopes;
        this.context = context;
        this.masking = masking;
        this.users = users;
        this.erase = erase;
        this.people = people;
    }

    // A mask and an erase change the person and the records of the person.
    Map<String, Set<String>> ofPerson(String personId) {
        return scopes.personData(personId);
    }

    Map<String, Set<String>> ofUser(String userId) {
        return scopes.userData(userId);
    }

    MaskRepresentation mask(Person person, boolean clearKeycloakName) {
        if (clearKeycloakName && person.userId() != null) {
            context.requireManageUser(person.userId());
        }
        String id = person.id();
        String maskedAt = masking.mask(id).toString();
        boolean cleared = clearKeycloakName && person.userId() != null && users.clearName(person.userId());
        var mask = new MaskRepresentation(id, people.get(id).person().version(), person.userId(), maskedAt,
                cleared);
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
