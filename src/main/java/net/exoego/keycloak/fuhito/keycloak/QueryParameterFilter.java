package net.exoego.keycloak.fuhito.keycloak;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import net.exoego.keycloak.fuhito.domain.NumberText;
import net.exoego.keycloak.fuhito.service.Page;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.utils.KeycloakSessionUtil;

/**
 * Rejects a request to the extension with a query parameter that its endpoint does not take, or with one query
 * parameter twice. JAX-RS would drop such a value with no error. It also rejects a whole number that is not in the
 * form of JSON or is outside its limits. JAX-RS would answer 404 or take {@code ５}, {@code +5}, and an empty value.
 * Only an endpoint of the extension is checked, so every endpoint of Keycloak takes its query as before.
 */
@Provider
public class QueryParameterFilter implements ContainerRequestFilter {

    static final String UNKNOWN = "invalid.unknown_parameter";
    static final String TWICE = "invalid.parameter_twice";
    static final String TYPE = "invalid.type";
    static final String OUT_OF_RANGE = "invalid.out_of_range";

    private static final Map<Class<?>, Limits> WHOLE = Map.of(
            Integer.class, new Limits(Integer.MIN_VALUE, Integer.MAX_VALUE),
            Integer.TYPE, new Limits(Integer.MIN_VALUE, Integer.MAX_VALUE),
            Long.class, new Limits(Long.MIN_VALUE, Long.MAX_VALUE),
            Long.TYPE, new Limits(Long.MIN_VALUE, Long.MAX_VALUE));

    // The limits that the OpenAPI document gives to a whole number in a query, by the name of the parameter. Its
    // type gives the others.
    private static final Map<String, Limits> DOCUMENTED = Map.of(
            "first", new Limits(0, Long.MAX_VALUE),
            "max", new Limits(0, Page.LARGEST_MAX),
            "after", new Limits(0, Long.MAX_VALUE));

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
                    problem.code(), problem.params(),
                    KeycloakRequestContext.locale(KeycloakSessionUtil.getKeycloakSession())));
        });
    }

    /**
     * The smallest and the largest whole number that a query parameter takes.
     *
     * @param min the smallest number
     * @param max the largest number
     */
    record Limits(long min, long max) {
    }

    /**
     * Why the method cannot take the query.
     *
     * @param code the error code
     * @param params the params of the error, with the name of the parameter in {@code field}
     */
    record Problem(String code, Map<String, String> params) {
    }

    /**
     * Returns the error of the first query parameter that the method cannot take.
     *
     * @param method the resource method
     * @param query the query parameters of the request by name
     * @return the error, or empty when the method takes the query
     */
    static Optional<Problem> problem(Method method, Map<String, List<String>> query) {
        Map<String, Class<?>> taken = taken(method);
        for (Map.Entry<String, List<String>> parameter : new TreeMap<>(query).entrySet()) {
            String name = parameter.getKey();
            if (!taken.containsKey(name)) {
                return Optional.of(new Problem(UNKNOWN, Map.of("field", name)));
            }
            if (parameter.getValue().size() > 1) {
                return Optional.of(new Problem(TWICE, Map.of("field", name)));
            }
            Optional<Limits> limits = limits(taken.get(name), name);
            String value = parameter.getValue().getFirst();
            if (limits.isPresent() && !NumberText.isWhole(value)) {
                return Optional.of(new Problem(TYPE, Map.of("field", name, "type", "integer")));
            }
            if (limits.isPresent() && NumberText.whole(value, limits.get().min(), limits.get().max()).isEmpty()) {
                return Optional.of(new Problem(OUT_OF_RANGE, Map.of("field", name,
                        "min", Long.toString(limits.get().min()), "max", Long.toString(limits.get().max()))));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the limits of a query parameter that is a whole number.
     *
     * @param type the type of the parameter of the method
     * @param name the name of the parameter
     * @return the limits, or empty for a parameter that is no whole number
     */
    static Optional<Limits> limits(Class<?> type, String name) {
        Limits whole = WHOLE.get(type);
        if (whole == null) {
            return Optional.empty();
        }
        Limits documented = DOCUMENTED.getOrDefault(name, whole);
        long min = Math.max(whole.min(), documented.min());
        long max = Math.min(whole.max(), documented.max());
        return Optional.of(new Limits(min, max));
    }

    /**
     * Returns the query parameters that a resource method takes, with their types. A write also takes the change
     * order, which the request context reads.
     *
     * @param method the resource method
     * @return the types by name
     */
    static Map<String, Class<?>> taken(Method method) {
        Map<String, Class<?>> names = new HashMap<>();
        for (Parameter parameter : method.getParameters()) {
            QueryParam name = parameter.getAnnotation(QueryParam.class);
            if (name != null) {
                names.put(name.value(), parameter.getType());
            }
        }
        if (!method.isAnnotationPresent(GET.class)) {
            names.put(KeycloakRequestContext.CHANGE_ORDER_PARAMETER, String.class);
        }
        return names;
    }
}
