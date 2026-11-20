package net.exoego.keycloak.fuhito.keycloak;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.utils.KeycloakSessionUtil;

/**
 * Rejects a request to the extension with a query parameter that its endpoint does not take, or with one query
 * parameter twice. JAX-RS would drop such a value with no error. Only an endpoint of the extension is checked, so
 * every endpoint of Keycloak takes its query as before.
 */
@Provider
public class QueryParameterFilter implements ContainerRequestFilter {

    static final String UNKNOWN = "invalid.unknown_parameter";
    static final String TWICE = "invalid.parameter_twice";

    private static final String EXTENSION = "net.exoego.keycloak.fuhito.";

    // Quarkus REST runs a request filter only for the method that answers, after the resource locators of Keycloak.
    // So Keycloak has checked the token and the realm of the caller.
    @Context
    ResourceInfo endpoint;

    /**
     * Creates the filter.
     */
    public QueryParameterFilter() {
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (!endpoint.getResourceClass().getName().startsWith(EXTENSION)) {
            return;
        }
        problem(endpoint.getResourceMethod(), request.getUriInfo().getQueryParameters()).ifPresent(problem -> {
            throw new ErrorResponseException(KeycloakRequestContext.error(Response.Status.BAD_REQUEST,
                    problem.getKey(), Map.of("field", problem.getValue()),
                    KeycloakRequestContext.locale(KeycloakSessionUtil.getKeycloakSession())));
        });
    }

    /**
     * Returns the error code and the name of the first query parameter that the method cannot take.
     *
     * @param method the resource method
     * @param query the query parameters of the request by name
     * @return the code and the name, or empty when the method takes the query
     */
    static Optional<Map.Entry<String, String>> problem(Method method, Map<String, List<String>> query) {
        Set<String> taken = taken(method);
        for (Map.Entry<String, List<String>> parameter : new TreeMap<>(query).entrySet()) {
            if (!taken.contains(parameter.getKey())) {
                return Optional.of(Map.entry(UNKNOWN, parameter.getKey()));
            }
            if (parameter.getValue().size() > 1) {
                return Optional.of(Map.entry(TWICE, parameter.getKey()));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the names of the query parameters that a resource method takes. A write also takes the change order,
     * which the request context reads.
     *
     * @param method the resource method
     * @return the names
     */
    static Set<String> taken(Method method) {
        Set<String> names = new HashSet<>();
        for (Parameter parameter : method.getParameters()) {
            QueryParam name = parameter.getAnnotation(QueryParam.class);
            if (name != null) {
                names.add(name.value());
            }
        }
        if (!method.isAnnotationPresent(GET.class)) {
            names.add(KeycloakRequestContext.CHANGE_ORDER_PARAMETER);
        }
        return names;
    }
}
