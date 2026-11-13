package net.exoego.keycloak.fuhito;

import java.io.File;
import java.util.List;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A database user that may not create the btree_gist extension.
 */
class RestrictedDatabaseIT {

    @Test
    void keycloakStartsWithoutTheConstraintsAndAddsThemLater() throws Exception {
        try (Network network = Network.newNetwork();
                PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
                        .withNetwork(network)
                        .withNetworkAliases("restricted-postgres")
                        .withDatabaseName("keycloak")
                        .withUsername("owner")
                        .withPassword("owner")) {
            postgres.start();
            // This user may use the public schema, and may not create extensions in the database.
            psql(postgres, "create role limited login password 'limited'");
            psql(postgres, "grant all on schema public to limited");

            try (KeycloakContainer keycloak =
                    new KeycloakContainer("quay.io/keycloak/keycloak:" + System.getProperty("keycloak.version"))
                            .withNetwork(network)
                            .withProviderLibsFrom(List.of(new File(System.getProperty("provider.jar"))))
                            .withEnv("KC_DB", "postgres")
                            .withEnv("KC_DB_URL", "jdbc:postgresql://restricted-postgres:5432/keycloak")
                            .withEnv("KC_DB_USERNAME", "limited")
                            .withEnv("KC_DB_PASSWORD", "limited")) {
                keycloak.start();

                assertEquals("0", psql(postgres, "select count(*) from pg_extension where extname = 'btree_gist'"));
                assertEquals("0", psql(postgres, "select count(*) from pg_constraint where conname like 'ex_fuhito_%'"));
                assertEquals("5", psql(postgres, "select count(*) from information_schema.tables"
                        + " where table_name like 'fuhito\\_%'"));
            }

            // After an administrator creates the extension, the next start adds the constraints.
            psql(postgres, "create extension btree_gist");
            try (KeycloakContainer keycloak =
                    new KeycloakContainer("quay.io/keycloak/keycloak:" + System.getProperty("keycloak.version"))
                            .withNetwork(network)
                            .withProviderLibsFrom(List.of(new File(System.getProperty("provider.jar"))))
                            .withEnv("KC_DB", "postgres")
                            .withEnv("KC_DB_URL", "jdbc:postgresql://restricted-postgres:5432/keycloak")
                            .withEnv("KC_DB_USERNAME", "limited")
                            .withEnv("KC_DB_PASSWORD", "limited")) {
                keycloak.start();

                assertEquals("2", psql(postgres, "select count(*) from pg_constraint where conname like 'ex_fuhito_%'"));
            }
        }
    }

    private static String psql(PostgreSQLContainer postgres, String sql) throws Exception {
        var result = postgres.execInContainer("psql", "-U", "owner", "-d", "keycloak", "-tAc", sql);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().trim();
    }
}
