package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the people of one realm.
 */
public class PersonService {


    /**
     * The person of a user, and whether this call created it.
     *
     * @param person the person
     * @param created the new person, or null when the person was there already
     */
    public record Resolved(Person person, PersonView created) {
    }

    /**
     * A person after a write, and the records whose read the write changed.
     *
     * @param view the person after the write
     * @param propagated the records whose read gives the user of the person, when the write changed the user. Their
     *         versions are counted up. None when the write kept the user.
     */
    public record Replaced(PersonView view, List<PropagatedChange> propagated) {
    }

    private final PersonStore store;
    private final ExternalIdStore externalIdStore;
    private final ExternalIdWrites externalIds;
    private final UserDirectory users;
    private final Predicate<String> inUse;
    private final AttributeRules attributeRules;

    /**
     * Creates the service.
     *
     * @param store the storage of people of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param users the users of the realm
     * @param inUse tells whether an employment, a qualification, or a reporting line names the person with an ID
     */
    public PersonService(PersonStore store, ExternalIdStore externalIds, UserDirectory users,
            Predicate<String> inUse) {
        this(store, externalIds, users, inUse, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of people of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param users the users of the realm
     * @param inUse tells whether an employment, a qualification, or a reporting line names the person with an ID
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public PersonService(PersonStore store, ExternalIdStore externalIds, UserDirectory users,
            Predicate<String> inUse, AttributeRules attributeRules) {
        this.attributeRules = attributeRules;
        this.store = store;
        this.externalIdStore = externalIds;
        this.externalIds = new ExternalIdWrites(externalIds, ExternalId.Kind.PERSON);
        this.users = users;
        this.inUse = inUse;
    }

    /**
     * Returns the check of free attributes of these operations, so that an import checks the names of a row with it.
     *
     * @return the check
     */
    AttributeRules attributeRules() {
        return attributeRules;
    }

    /**
     * Returns the same operations that read the definitions of attributes once for each kind of resource. They
     * suit a list of writes that changes no definition.
     *
     * @return the operations
     */
    PersonService remembering() {
        return new PersonService(store, externalIdStore, users, inUse, attributeRules.remembering());
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
        PersonView created = create(new PersonValues(userId, null, null, null, null, null));
        return new Resolved(created.person(), created);
    }

    /**
     * Finds the person that is linked to a Keycloak user.
     *
     * @param userId the ID of the user
     * @return the person, or empty when no person is linked to the user
     */
    public Optional<Person> ofUser(String userId) {
        return store.findByUser(userId);
    }

    /**
     * Finds a person by ID.
     *
     * @param id the ID of the person
     * @return the person, or empty when the realm has none with the ID
     */
    public Optional<Person> find(String id) {
        return store.find(id);
    }

    /**
     * Finds several people by ID, with one read.
     *
     * @param ids the IDs of the people
     * @return the people that the realm has, by ID
     */
    Map<String, Person> findAll(Collection<String> ids) {
        Map<String, Person> found = new HashMap<>();
        store.findAll(ids).forEach(person -> found.put(person.id(), person));
        return found;
    }

    /**
     * Creates a person.
     *
     * @param request the values of the request. The person keeps the source as the system that made it.
     * @return the new person
     * @throws FuhitoException when a value is bad, or the user or the external ID belongs to another person
     */
    public PersonView create(PersonValues request) {
        NewPeople people = new NewPeople(externalIds);
        PersonView created = people.create(request);
        people.insert();
        return created;
    }

    /**
     * Starts new people of one list of writes.
     *
     * @param ids finds the person that an ID of a system names
     * @return the new people
     */
    NewPeople newPeople(ExternalIdWrites.Lookup ids) {
        return new NewPeople(new ExternalIdWrites(externalIdStore, ExternalId.Kind.PERSON, ids));
    }

    /**
     * The new people of one list of writes. Each one is checked against the stored people and the new people
     * before it. All of them are stored together.
     */
    final class NewPeople {

        private final ExternalIdWrites ids;
        private final Map<String, Optional<Person>> byUser = new HashMap<>();
        private final List<Person> people = new ArrayList<>();
        private final List<ExternalId> rows = new ArrayList<>();

        private NewPeople(ExternalIdWrites ids) {
            this.ids = ids;
        }

        /**
         * Reads the people of some users with one read.
         *
         * @param userIds the IDs of the users
         */
        void readUsers(Collection<String> userIds) {
            List<String> unread = userIds.stream().distinct().filter(id -> !byUser.containsKey(id)).toList();
            unread.forEach(id -> byUser.put(id, Optional.empty()));
            store.findByUsers(unread).forEach(person -> byUser.put(person.userId(), Optional.of(person)));
        }

        /**
         * Finds the person of a user, a new one of the list included.
         *
         * @param userId the ID of the user
         * @return the person, or empty when the user has none
         */
        Optional<Person> ofUser(String userId) {
            return byUser.computeIfAbsent(userId, store::findByUser);
        }

        /**
         * Lets the next question about some users read the stored people again.
         *
         * @param userIds the IDs of the users, null ones included
         */
        void forget(String... userIds) {
            for (String userId : userIds) {
                if (userId != null) {
                    byUser.remove(userId);
                }
            }
        }

        /**
         * Checks a new person as {@link PersonService#create} does, and keeps it for {@link #insert}.
         *
         * @param request the values of the request
         * @return the new person
         */
        PersonView create(PersonValues request) {
            Checks.required("body", request);
            ExternalId.Pair externalId = ids.check(null, request.source(), request.externalId());
            Person person = checked(UUID.randomUUID().toString(), request,
                    Checks.optionalCode("source", request.source()), 1, this::ofUser);
            people.add(person);
            if (person.userId() != null) {
                byUser.put(person.userId(), Optional.of(person));
            }
            ids.forNew(person.id(), externalId).ifPresent(rows::add);
            return view(person);
        }

        /**
         * Stores the new people that are not stored yet.
         */
        void insert() {
            people.forEach(store::insert);
            externalIdStore.insertAll(List.copyOf(rows));
            people.clear();
            rows.clear();
        }
    }

    /**
     * Replaces the names, the attributes, and the link to a Keycloak user. An external ID of the request is stored
     * as the ID that the system of its source has for the person. The person keeps the system that made it.
     * A link, an unlink, or another user changes the read of each record of the person, so their versions count up.
     *
     * @param id the ID
     * @param request the new values
     * @return the changed person, and the records whose read changed
     * @throws FuhitoException when the person does not exist, a value is bad, or the external ID belongs to another
     *         person
     */
    public Replaced replace(String id, PersonValues request) {
        Checks.required("body", request);
        Person current = get(id).person();
        ExternalId.Pair externalId = externalIds.check(id, request.source(), request.externalId());
        Person replaced = checked(id, request, current.source(), current.version(), store::findByUser);
        store.update(replaced);
        externalIds.store(id, externalId);
        List<PropagatedChange> propagated = Objects.equals(current.userId(), replaced.userId()) ? List.of()
                : store.countUpReadsOfUser(id);
        return new Replaced(get(id), propagated);
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
     * @param max the largest number of people to return, or null for 100. At most 1000 are returned.
     * @return the people
     */
    public List<PersonView> list(String userId, Integer first, Integer max) {
        return list(userId, first, max, null);
    }

    /**
     * Lists the people of the realm, ordered by ID, from a cursor on.
     *
     * @param userId the ID of a Keycloak user, to return only the person linked to it, or null for everyone
     * @param first the number of people to skip, or null for 0
     * @param max the largest number of people to return, or null for 100. At most 1000 are returned.
     * @param after the cursor of the page before, or null to start at the first person
     * @return the people
     * @throws FuhitoException when the cursor is bad, or the request has a cursor and a number to skip
     */
    public List<PersonView> list(String userId, Integer first, Integer max, String after) {
        return list(userId, null, null, first, max, after);
    }

    /**
     * Finds people of the realm, ordered by ID, from a cursor on.
     *
     * @param userId the ID of the Keycloak user that the person is linked to, or null for any
     * @param text a text that a name of the person or of its Keycloak user has, in any case, or null for any
     * @param staffNumber a staff number that an employment of the person has, or null for any
     * @param first the number of people to skip, or null for 0
     * @param max the largest number of people to return, or null for 100. At most 1000 are returned.
     * @param after the cursor of the page before, or null to start at the first person
     * @return the people
     * @throws FuhitoException when the cursor is bad, or the request has a cursor and a number to skip
     */
    public List<PersonView> list(String userId, String text, String staffNumber, Integer first, Integer max,
            String after) {
        Cursor.requireAlone(after, first);
        Page page = Page.of(first, max);
        String afterId = after == null ? null : Cursor.decode(Cursor.PEOPLE, after).getFirst();
        String search = stripped(text);
        String number = stripped(staffNumber);
        List<Person> people = userId == null && search == null && number == null
                ? store.list(afterId, page.first(), page.max())
                : store.search(userId, search, number, afterId, page.first(), page.max());
        return people.stream().map(this::view).toList();
    }

    /**
     * Counts the people that {@link #list(String, String, String, Integer, Integer, String)} finds on all pages
     * together.
     *
     * @param userId the ID of the Keycloak user that the person is linked to, or null for any
     * @param text a text that a name of the person or of its Keycloak user has, in any case, or null for any
     * @param staffNumber a staff number that an employment of the person has, or null for any
     * @return the number of people
     */
    public long count(String userId, String text, String staffNumber) {
        return store.count(userId, stripped(text), stripped(staffNumber));
    }

    private static String stripped(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Person checked(String id, PersonValues request, String source, long version,
            Function<String, Optional<Person>> ofUser) {
        Localized<PersonName> names = PersonName.check(request.primaryLanguage(), request.names());
        String userId = request.userId();
        if (userId == null && names == null) {
            throw FuhitoException.invalid("person.name_required", Map.of());
        }
        if (userId != null) {
            if (!users.exists(userId)) {
                throw FuhitoException.invalid("person.user_unknown", Map.of("userId", userId));
            }
            Optional<Person> linked = ofUser.apply(userId).filter(other -> !other.id().equals(id));
            if (linked.isPresent()) {
                throw FuhitoException.conflict("person.user_taken",
                        Map.of("userId", userId, "personId", linked.get().id()));
            }
        }
        return new Person(id, userId, names,
                attributeRules.check(AttributeDefinition.Target.PERSON, request.attributes()), source, version);
    }

    private PersonView view(Person person) {
        if (person.names() != null || person.userId() == null) {
            return new PersonView(person, null);
        }
        return new PersonView(person, users.displayName(person.userId()).orElse(null));
    }
}
