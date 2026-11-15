-- Gives every person of the seed a Keycloak user, for the projection and the sync from user attributes.
-- It writes to tables of Keycloak itself, so it fits the Keycloak version of the run and may not fit another.
-- psql sets :realm to the ID of the realm, :units to the number of org units below the company, and :people
-- to the number of people.

INSERT INTO user_entity (id, email_constraint, email_verified, enabled, realm_id, username, created_timestamp, not_before)
SELECT 'k-' || n, 'k-' || n, false, true, :'realm', 'bench-user-' || n, 0, 0 FROM generate_series(0, :people - 1) n;

-- The department of a user is the code of the org unit where the person of the seed works.
INSERT INTO user_attribute (id, name, value, user_id)
SELECT 'ka-' || n, 'department', 'c' || (n % (:units + 1)), 'k-' || n FROM generate_series(0, :people - 1) n;

UPDATE fuhito_person SET user_id = 'k-' || substr(id, 3) WHERE realm_id = :'realm';

ANALYZE;
