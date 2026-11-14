package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.service.PersonView.NameSource;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the people of one realm.
 */
public class PersonService {

    private static final int DEFAULT_MAX = 100;

    /**
     * The person of a user, and whether this call created it.
     *
     * @param person the person
     * @param created the new person, or null when the person was there already
     */
    public record Resolved(Person person, PersonView created) {
    }

    private final PersonStore store;
    private final UserDirectory users;
    private final Predicate<String> inUse;

    /**
     * Creates the service.
     *
     * @param store the storage of people of the realm
     * @param users the users of the realm
     * @param inUse tells whether an employment, a qualification, or a reporting line names the person with an ID
     */
    public PersonService(PersonStore store, UserDirectory users, Predicate<String> inUse) {
        this.store = store;
        this.users = users;
        this.inUse = inUse;
    }

    /**
     * Returns the person that is linked to a Keycloak user. Creates one when the user has none yet.
     * A write that names a user by ID uses this.
     *
     * @param userId the ID of the user
     * @return the person
     * @throws FuhitoException when no person is linked to the user and the user does not exist
     */
    public Resolved forUser(String userId) {
        Checks.required("userId", userId);
        Optional<Person> linked = store.findByUser(userId);
        if (linked.isPresent()) {
            return new Resolved(linked.get(), null);
        }
        PersonView created = create(new PersonValues(userId, null, null, null, null, null, null));
        return new Resolved(created.person(), created);
    }

    /**
     * Creates a person.
     *
     * @param request the values of the request
     * @return the new person
     * @throws FuhitoException when a value is bad, or the user or the external ID belongs to another person
     */
    public PersonView create(PersonValues request) {
        Checks.required("body", request);
        String source = Checks.optionalCode("source", request.source());
        Person person = checked(UUID.randomUUID().toString(), request, request.externalId(), source);
        if (source != null && person.externalId() != null
                && store.findByExternalId(source, person.externalId()).isPresent()) {
            throw FuhitoException.conflict("person.external_id_taken",
                    Map.of("source", source, "externalId", person.externalId()));
        }
        store.insert(person);
        return view(person);
    }

    /**
     * Replaces the names, the attributes, and the link to a Keycloak user.
     *
     * @param id the ID
     * @param request the new values
     * @return the changed person
     * @throws FuhitoException when the person does not exist or a value is bad
     */
    public PersonView replace(String id, PersonValues request) {
        Checks.required("body", request);
        Person current = get(id).person();
        Person changed = checked(id, request, current.externalId(), current.source());
        store.update(changed);
        return view(changed);
    }

    /**
     * Returns a person.
     *
     * @param id the ID
     * @return the person
     * @throws FuhitoException when the realm has no such person
     */
    public PersonView get(String id) {
        return store.find(id).map(this::view)
                .orElseThrow(() -> FuhitoException.notFound("person.not_found", Map.of("id", id)));
    }

    /**
     * Deletes a person.
     *
     * @param id the ID
     * @throws FuhitoException when the person does not exist or something still names the person
     */
    public void delete(String id) {
        get(id);
        if (inUse.test(id)) {
            throw FuhitoException.conflict("person.in_use", Map.of("id", id));
        }
        store.delete(id);
    }

    /**
     * Lists the people of the realm, ordered by ID.
     *
     * @param userId the ID of a Keycloak user, to return only the person linked to it, or null for everyone
     * @param first the number of people to skip, or null for 0
     * @param max the largest number of people to return, or null for 100
     * @return the people
     */
    public List<PersonView> list(String userId, Integer first, Integer max) {
        if (userId != null) {
            return store.findByUser(userId).map(this::view).stream().toList();
        }
        return store.list(first == null ? 0 : Math.max(0, first), max == null ? DEFAULT_MAX : Math.max(0, max))
                .stream()
                .map(this::view)
                .toList();
    }

    private Person checked(String id, PersonValues request, String externalId, String source) {
        String name = Checks.optionalName("name", request.name());
        String userId = request.userId();
        if (userId == null && name == null) {
            throw FuhitoException.invalid("person.name_required", Map.of());
        }
        if (userId != null) {
            if (!users.exists(userId)) {
                throw FuhitoException.invalid("person.user_unknown", Map.of("userId", userId));
            }
            Optional<Person> linked = store.findByUser(userId).filter(other -> !other.id().equals(id));
            if (linked.isPresent()) {
                throw FuhitoException.conflict("person.user_taken",
                        Map.of("userId", userId, "personId", linked.get().id()));
            }
        }
        return new Person(id, userId, name, Checks.localizedNames("names", request.names()),
                Checks.optionalName("phoneticName", request.phoneticName()),
                request.attributes() == null ? Map.of() : request.attributes(), externalId, source);
    }

    private PersonView view(Person person) {
        if (person.name() != null) {
            return new PersonView(person, person.name(), NameSource.RECORD);
        }
        return Optional.ofNullable(person.userId())
                .flatMap(users::displayName)
                .map(name -> new PersonView(person, name, NameSource.KEYCLOAK))
                .orElseGet(() -> new PersonView(person, null, null));
    }
}
