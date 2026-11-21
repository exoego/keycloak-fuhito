package net.exoego.keycloak.fuhito.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition.Target;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.service.ImportDocument.PersonRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The list of people of one document.
 */
final class PersonImport {

    private static final String PEOPLE = "people";

    private final PeopleImport document;
    private final ListIds ids;
    private final PersonService service;
    private final AttributeRules rules;
    private final PersonService.NewPeople created;
    private final Map<String, Optional<Person>> stored = new HashMap<>();

    PersonImport(PeopleImport document) {
        this.document = document;
        this.ids = new ListIds(document.externalIds);
        this.service = document.people.personService().remembering();
        this.rules = service.attributeRules();
        this.created = service.newPeople((source, value) -> ids.resourceOf(ExternalId.Kind.PERSON, source, value));
    }

    void run(List<PersonRow> rows) {
        document.report.startList(PEOPLE);
        read(rows);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                PersonRow row = EmptyValues.checked(rows.get(i));
                document.requireFirst(seen, row.source(), row.externalId(), row.id());
                document.report.count(PEOPLE, apply(row));
            } catch (FuhitoException e) {
                document.report.error(PEOPLE, i, e);
            }
            document.rowDone.run();
        }
        created.insert();
        document.rowDone.run();
    }

    // The stored people that the rows name, and the people of the users that they name by ID.
    private void read(List<PersonRow> rows) {
        List<PersonRow> given = rows.stream().filter(EmptyValues::passes).toList();
        given.forEach(row -> ids.want(ExternalId.Kind.PERSON, document.sourceOf(row.source()), row.externalId()));
        ids.read();
        Set<String> personIds = new HashSet<>();
        Set<String> userIds = new HashSet<>();
        for (PersonRow row : given) {
            personIds.addAll(document.named(ids, row.id(), ExternalId.Kind.PERSON, row.source(), row.externalId()));
            if (row.userId() != null) {
                userIds.add(row.userId());
            }
        }
        Map<String, Person> found = service.findAll(personIds);
        personIds.forEach(id -> stored.put(id, Optional.ofNullable(found.get(id))));
        created.readUsers(userIds);
    }

    private Optional<Person> find(String id) {
        return stored.computeIfAbsent(id, service::find);
    }

    private Outcome apply(PersonRow row) {
        String source = Checks.code("source", row.source() == null ? document.defaultSource : row.source());
        String userId = row.userId() != null || row.username() != null
                ? document.userId(row.userId(), row.username()) : null;
        Optional<Person> before = row.id() == null
                ? ids.resourceOf(ExternalId.Kind.PERSON, source, row.externalId()).flatMap(this::find)
                : Optional.of(byId(row, source));
        if (before.isEmpty()) {
            // A row never finds a person by its user. The file must name the person of the user.
            Optional<Person> ofUser = userId == null ? Optional.empty() : created.ofUser(userId);
            if (ofUser.isPresent()) {
                throw FuhitoException.conflict("import.user_taken",
                        Map.of("userId", userId, "personId", ofUser.get().id()));
            }
            PersonValues values = new PersonValues(userId, row.primaryLanguage(),
                    Merged.names(row, row.names(), null, Merged.PERSON_NAMES),
                    Merged.attributes(row, row.attributes(), null, rules, Target.PERSON), row.externalId(), source);
            PersonView view = ids.create(ExternalId.Kind.PERSON, source, row.externalId(),
                    () -> created.create(values), made -> made.person().id());
            document.report.write(new Write.OfPerson(true, view));
            return Outcome.CREATED;
        }
        Person person = before.get();
        // A row that leaves out the user keeps the link, and null removes it.
        boolean keepsUser = !row.gives("userId", row.userId()) && !row.gives("username", row.username());
        String linked = keepsUser ? person.userId() : userId;
        Localized<PersonName> names = person.names();
        PersonValues wanted = new PersonValues(linked,
                row.or("primaryLanguage", row.primaryLanguage(), names == null ? null : names.primaryLanguage()),
                Merged.names(row, row.names(), names == null ? Map.of() : names.byLanguage(), Merged.PERSON_NAMES),
                Merged.attributes(row, row.attributes(), person.attributes(), rules, Target.PERSON), row.externalId(),
                source);
        boolean same = Objects.equals(linked, person.userId())
                && Objects.equals(PersonName.check(wanted.primaryLanguage(), wanted.names()), person.names())
                && (wanted.attributes() == null ? Map.of() : wanted.attributes()).equals(person.attributes());
        if (same) {
            return Outcome.UNCHANGED;
        }
        created.insert();
        PersonView changed = service.replace(person.id(), wanted);
        stored.remove(person.id());
        created.forget(person.userId(), changed.person().userId());
        document.report.write(new Write.OfPerson(false, changed));
        return Outcome.CHANGED;
    }

    // A row with an ID changes that person. An external ID beside it must be one of that person.
    private Person byId(PersonRow row, String source) {
        Person person = find(row.id())
                .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id())));
        ImportMatch.source(row.id(), person.source(), row.source(), row.externalId());
        PeopleImport.requireNames(ids, ExternalId.Kind.PERSON, row.id(), source, row.externalId());
        return person;
    }
}
