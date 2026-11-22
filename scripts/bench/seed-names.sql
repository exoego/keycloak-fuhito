-- Gives every user of the seed a first name and a last name, and takes the names out of the person records. So the
-- read of each person gives the name of its user, and a change of the order of names changes every read.
-- It writes to a table of Keycloak itself, so it fits the Keycloak version of the run and may not fit another.
-- psql sets :realm to the ID of the realm.

UPDATE user_entity SET first_name = 'Given ' || substr(id, 3), last_name = 'Family ' || substr(id, 3)
WHERE realm_id = :'realm' AND id LIKE 'k-%';

DELETE FROM fuhito_person_name WHERE realm_id = :'realm';

ANALYZE;
