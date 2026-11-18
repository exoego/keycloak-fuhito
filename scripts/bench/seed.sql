-- Fills one realm with a large organization. psql sets :realm to the ID of the realm, :units to the number of
-- org units below the company, and :people to the number of people.
-- 1 company, 50 divisions, 500 departments, and teams for the rest. One move for 30% of the people.
-- The realm has its default hierarchy from its start.

INSERT INTO fuhito_change_log_counter (realm_id, last_sequence) VALUES (:'realm', :people * 4);
INSERT INTO fuhito_ref_item (id, realm_id, kind, code, display_order, attributes, active, legal_entity) VALUES
  ('bench-company', :'realm', 'UNIT_TYPE', 'company', 0, '{}', true, true),
  ('bench-unit', :'realm', 'UNIT_TYPE', 'unit', 0, '{}', true, false),
  ('bench-nurse', :'realm', 'OCCUPATION', 'nurse', 0, '{}', true, false),
  ('bench-full', :'realm', 'EMPLOYMENT_TYPE', 'full-time', 0, '{}', true, false);
-- The names of each item hold for every date.
INSERT INTO fuhito_ref_item_name (id, realm_id, ref_item_id, valid_from, valid_to, name, names_i18n) VALUES
  ('bench-company', :'realm', 'bench-company', NULL, NULL, 'Company', '{}'),
  ('bench-unit', :'realm', 'bench-unit', NULL, NULL, 'Unit', '{}'),
  ('bench-nurse', :'realm', 'bench-nurse', NULL, NULL, 'Nurse', '{}'),
  ('bench-full', :'realm', 'bench-full', NULL, NULL, 'Full time', '{}');

-- n = 0 is the company. 1 to 50 are divisions, 51 to 550 departments, and the rest are teams.
CREATE TEMP TABLE bench_unit AS
SELECT n, 'u-' || n AS id,
       CASE WHEN n = 0 THEN NULL
            WHEN n <= 50 THEN 'u-0'
            WHEN n <= 550 THEN 'u-' || (1 + (n - 51) / 10)
            ELSE 'u-' || (51 + (n - 551) % 500) END AS parent
FROM generate_series(0, :units) n;

INSERT INTO fuhito_org_unit (id, realm_id) SELECT id, :'realm' FROM bench_unit;
-- One unit in five got a new name in 2023, so it has two versions.
INSERT INTO fuhito_org_unit_version (id, realm_id, org_unit_id, valid_from, valid_to, code, name, names_i18n, short_names_i18n, phonetic_name, unit_type_id, display_order, attributes)
SELECT 'v1-' || n, :'realm', id, DATE '2020-04-01', CASE WHEN n % 5 = 1 THEN DATE '2023-04-01' END,
       'c' || n, 'Unit ' || n, '{}', '{}', 'unit ' || lpad(n::text, 5, '0'),
       CASE WHEN n = 0 THEN 'bench-company' ELSE 'bench-unit' END, n % 7, '{}'
FROM bench_unit;
INSERT INTO fuhito_org_unit_version (id, realm_id, org_unit_id, valid_from, valid_to, code, name, names_i18n, short_names_i18n, phonetic_name, unit_type_id, display_order, attributes)
SELECT 'v2-' || n, :'realm', id, DATE '2023-04-01', NULL, 'c' || n, 'New unit ' || n, '{}', '{}', 'unit ' || lpad(n::text, 5, '0'),
       'bench-unit', n % 7, '{}'
FROM bench_unit WHERE n % 5 = 1;
INSERT INTO fuhito_org_unit_link (id, realm_id, hierarchy_id, child_id, parent_id, valid_from, valid_to)
SELECT 'l-' || n, :'realm', h.id, u.id, u.parent, DATE '2020-04-01', NULL
FROM bench_unit u JOIN fuhito_hierarchy h ON h.realm_id = :'realm' AND h.code = 'default' WHERE u.parent IS NOT NULL;

-- The company is at site 0 with cost center 0. Each division has a cost center, and one division in five a site of
-- its own. One team in ten is at site 3.
INSERT INTO fuhito_ref_item (id, realm_id, kind, code, display_order, attributes, active, legal_entity)
SELECT 'bench-wl-' || n, :'realm', 'WORK_LOCATION', 'site-' || n, 0, '{}', true, false
FROM generate_series(0, 10) n;
INSERT INTO fuhito_ref_item_name (id, realm_id, ref_item_id, valid_from, valid_to, name, names_i18n)
SELECT 'bench-wl-' || n, :'realm', 'bench-wl-' || n, NULL, NULL, 'Site ' || n, '{}'
FROM generate_series(0, 10) n;
INSERT INTO fuhito_ref_item (id, realm_id, kind, code, display_order, attributes, active, legal_entity)
VALUES ('bench-home', :'realm', 'WORK_LOCATION', 'home', 0, '{}', true, false);
INSERT INTO fuhito_ref_item_name (id, realm_id, ref_item_id, valid_from, valid_to, name, names_i18n)
VALUES ('bench-home', :'realm', 'bench-home', NULL, NULL, 'Home', '{}');
INSERT INTO fuhito_ref_item (id, realm_id, kind, code, display_order, attributes, active, legal_entity)
SELECT 'bench-cc-' || n, :'realm', 'COST_CENTER', 'cc-' || n, 0, '{}', true, false
FROM generate_series(0, 50) n;
INSERT INTO fuhito_ref_item_name (id, realm_id, ref_item_id, valid_from, valid_to, name, names_i18n)
SELECT 'bench-cc-' || n, :'realm', 'bench-cc-' || n, NULL, NULL, 'Cost center ' || n, '{}'
FROM generate_series(0, 50) n;
UPDATE fuhito_org_unit_version v SET work_location_id = 'bench-wl-0', cost_center_id = 'bench-cc-0' FROM bench_unit u
WHERE v.realm_id = :'realm' AND v.org_unit_id = u.id AND u.n = 0;
UPDATE fuhito_org_unit_version v SET cost_center_id = 'bench-cc-' || u.n,
  work_location_id = CASE WHEN u.n % 5 = 0 THEN 'bench-wl-' || (u.n / 5) END
FROM bench_unit u WHERE v.realm_id = :'realm' AND v.org_unit_id = u.id AND u.n BETWEEN 1 AND 50;
UPDATE fuhito_org_unit_version v SET work_location_id = 'bench-wl-3' FROM bench_unit u
WHERE v.realm_id = :'realm' AND v.org_unit_id = u.id AND u.n > 550 AND u.n % 10 = 3;

INSERT INTO fuhito_person (id, realm_id, name, names_i18n, attributes)
SELECT 'p-' || n, :'realm', 'Person ' || n, '{}', '{}' FROM generate_series(0, :people - 1) n;
INSERT INTO fuhito_employment (id, realm_id, valid_from, staff_number, person_id, employer_id)
SELECT 'e-' || n, :'realm', DATE '2020-04-01', 'S' || n, 'p-' || n, 'u-0' FROM generate_series(0, :people - 1) n;
INSERT INTO fuhito_employment_terms (id, realm_id, employment_id, valid_from, employment_type_id, status, work_percent, attributes)
SELECT 't-' || n, :'realm', 'e-' || n, DATE '2020-04-01', 'bench-full', 'ACTIVE', 100, '{}' FROM generate_series(0, :people - 1) n;

-- Person n works in unit n % (units + 1) today. The first people are the heads of their units.
-- Three in ten were in another unit until 2024.
INSERT INTO fuhito_assignment (id, realm_id, assignment_id, employment_id, org_unit_id, valid_from, valid_to, occupation_id, is_head, is_primary, display_order, attributes)
SELECT 'a-' || n, :'realm', 'a-' || n, 'e-' || n, 'u-' || (n % (:units + 1)),
       CASE WHEN n % 10 < 3 THEN DATE '2024-04-01' ELSE DATE '2020-04-01' END, NULL,
       'bench-nurse', n <= :units, true, n % 20, '{}'
FROM generate_series(0, :people - 1) n;
INSERT INTO fuhito_assignment (id, realm_id, assignment_id, employment_id, org_unit_id, valid_from, valid_to, occupation_id, is_head, is_primary, display_order, attributes)
SELECT 'b-' || n, :'realm', 'b-' || n, 'e-' || n, 'u-' || ((n * 7) % (:units + 1)), DATE '2020-04-01', DATE '2024-04-01',
       'bench-nurse', false, true, 0, '{}'
FROM generate_series(0, :people - 1) n WHERE n % 10 < 3;
-- One person in ten works from home.
UPDATE fuhito_assignment SET work_location_id = 'bench-home' WHERE realm_id = :'realm' AND id LIKE 'a-%7';

INSERT INTO fuhito_change_log (id, realm_id, recorded_at, resource_type, resource_id, operation, resource_path, sequence_number)
SELECT 'c-' || n, :'realm', TIMESTAMP '2020-04-01' + n * INTERVAL '10 minutes', 'FUHITO_ASSIGNMENT', 'a-' || (n % :people),
       'UPDATE', 'fuhito/assignments/a-' || (n % :people), n
FROM generate_series(1, :people * 4) n;

ANALYZE;
