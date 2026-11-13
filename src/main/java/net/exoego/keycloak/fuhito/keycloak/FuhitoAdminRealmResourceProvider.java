package net.exoego.keycloak.fuhito.keycloak;

import java.util.Locale;

import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.rest.FuhitoResource;
import net.exoego.keycloak.fuhito.rest.RequestContext;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * Builds the root resource of the admin REST API for one request.
 */
public class FuhitoAdminRealmResourceProvider implements AdminRealmResourceProvider {

    /**
     * Creates the provider.
     */
    public FuhitoAdminRealmResourceProvider() {
    }

    @Override
    public Object getResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
            AdminEventBuilder adminEvent) {
        RequestContext context = new RequestContext() {
            @Override
            public void requireRead() {
                auth.users().requireView();
            }

            @Override
            public void requireWrite() {
                auth.users().requireManage();
            }

            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public RuntimeException failure(Response response) {
                return new ErrorResponseException(response);
            }
        };
        var entityManager = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        return new FuhitoResource(realm.getId(), context, entityManager);
    }

    @Override
    public void close() {
    }
}
