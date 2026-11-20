package net.exoego.keycloak.fuhito.rest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Messages;
import net.exoego.keycloak.fuhito.service.EmptyValues;

/**
 * Runs an operation and turns its failure into an error response.
 */
final class Calls {

    private record Missing(String code, String param) {
    }

    // The error of an ID that names no resource, by the first part of the path.
    private static final Map<String, Missing> MISSING = Map.ofEntries(
            Map.entry("assignments", new Missing("assignment.not_found", "id")),
            Map.entry("attribute-definitions", new Missing("attribute.not_found", "id")),
            Map.entry("change-orders", new Missing("change_order.not_found", "id")),
            Map.entry("employments", new Missing("employment.not_found", "id")),
            Map.entry("hierarchies", new Missing("hierarchy.not_found", "id")),
            Map.entry("org-units", new Missing("org_unit.not_found", "id")),
            Map.entry("people", new Missing("person.not_found", "id")),
            Map.entry("qualifications", new Missing("qualification.not_found", "id")),
            Map.entry("ref-items", new Missing("ref_item.not_found", "id")),
            Map.entry("ref-kinds", new Missing("ref_kind.not_found", "code")),
            Map.entry("reporting-lines", new Missing("reporting_line.not_found", "id")),
            Map.entry("unit-roles", new Missing("unit_role.not_found", "id")),
            Map.entry("users", new Missing("user.not_found", "userId")));

    private Calls() {
    }

    static <T> T run(RequestContext context, Supplier<T> operation) {
        try {
            requireStorableId(context);
            T result = operation.get();
            context.flush();
            return result;
        } catch (FuhitoException e) {
            throw failure(context, e);
        } catch (RuntimeException e) {
            if (!anotherWriteCameFirst(e)) {
                throw e;
            }
            throw failure(context, FuhitoException.conflict("version.conflict", Map.of()).causedBy(e));
        }
    }

    // PostgreSQL cannot hold NUL in a text, and fails a query that has it. So no resource has such an ID.
    private static void requireStorableId(RequestContext context) {
        List<String> parts = context.pathParts();
        Missing missing = parts.size() > 1 ? MISSING.get(parts.getFirst()) : null;
        if (missing != null && parts.get(1).indexOf('\0') >= 0) {
            throw FuhitoException.notFound(missing.code(), Map.of(missing.param(), parts.get(1)));
        }
    }

    // Another write changed a row with a version after this one read it. Keycloak wraps the error of JPA.
    private static boolean anotherWriteCameFirst(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException) {
                return true;
            }
        }
        return false;
    }

    private static RuntimeException failure(RequestContext context, FuhitoException e) {
        var body = new ErrorRepresentation(e.code(), e.params(),
                Messages.format(e.code(), e.params(), context.locale()));
        return context.failure(Response.status(status(e.reason()))
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build());
    }

    /**
     * Requires the body of a write, with no empty text and no empty list in any of its values.
     *
     * @param <B> the type of the body
     * @param body the body
     * @return the body
     * @throws FuhitoException when the body is missing, a text in it is empty or only spaces, or a list in it is
     *         empty
     */
    static <B extends Record> B body(B body) {
        return EmptyValues.withoutEmptyValues(Checks.required("body", body));
    }

    /**
     * Runs a write of one stored resource. It goes on only when the resource still has the version that the body
     * names.
     *
     * @param <T> the type of the response
     * @param <B> the type of the body
     * @param context the request
     * @param body the body of the request, with the version that the caller read
     * @param current reads the version of the resource that the path names
     * @param operation the write
     * @return the response
     */
    static <T, B extends Record & Versioned> T guarded(RequestContext context, B body, LongSupplier current,
            Supplier<T> operation) {
        return guarded(context, body, current, Map::of, operation);
    }

    /**
     * Runs a write of one stored resource that also changes other stored resources. It locks them all together
     * with the resource of the path, before it reads any of them. It goes on only when the resource of the path
     * still has the version that the body names.
     *
     * @param <T> the type of the response
     * @param <B> the type of the body
     * @param context the request
     * @param body the body of the request, with the version that the caller read
     * @param current reads the version of the resource that the path names
     * @param others finds the other resources that the write changes, by the first part of their path
     * @param operation the write
     * @return the response
     */
    static <T, B extends Record & Versioned> T guarded(RequestContext context, B body, LongSupplier current,
            Supplier<? extends Map<String, ? extends Collection<String>>> others, Supplier<T> operation) {
        return run(context, () -> {
            body(body);
            requireTarget(context, body.version(), current, others);
            return operation.get();
        });
    }

    /**
     * Runs a write of one stored resource that has no body, such as a {@code DELETE}. It goes on only when the
     * resource still has the version that the query names.
     *
     * @param <T> the type of the response
     * @param context the request
     * @param version the version that the caller read as text, or null when the query has none
     * @param current reads the version of the resource that the path names
     * @param operation the write
     * @return the response
     */
    static <T> T guarded(RequestContext context, String version, LongSupplier current, Supplier<T> operation) {
        return guarded(context, version, current, Map::of, operation);
    }

    /**
     * Runs a write with no body of one stored resource that also changes other stored resources, as
     * {@link #guarded(RequestContext, Record, LongSupplier, Supplier, Supplier)} does for a write with a body.
     *
     * @param <T> the type of the response
     * @param context the request
     * @param version the version that the caller read as text, or null when the query has none
     * @param current reads the version of the resource that the path names
     * @param others finds the other resources that the write changes, by the first part of their path
     * @param operation the write
     * @return the response
     */
    static <T> T guarded(RequestContext context, String version, LongSupplier current,
            Supplier<? extends Map<String, ? extends Collection<String>>> others, Supplier<T> operation) {
        return run(context, () -> {
            requireTarget(context, versionOf(version), current, others);
            return operation.get();
        });
    }

    /**
     * Reads the version in the query of a request.
     *
     * @param text the value of the query parameter, or null
     * @return the version, or null when the text is none or no number
     */
    static Long versionOf(String text) {
        // Jakarta REST would answer 404 to a query parameter of the type Long that is no number.
        try {
            return text == null ? null : Long.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void requireTarget(RequestContext context, Long version, LongSupplier current,
            Supplier<? extends Map<String, ? extends Collection<String>>> others) {
        // A second write with the same version waits here, and then reads the version that the first one wrote.
        context.lockTarget(others.get());
        Checks.version(version, current.getAsLong());
    }

    private static int status(FuhitoException.Reason reason) {
        return switch (reason) {
            case INVALID -> Response.Status.BAD_REQUEST.getStatusCode();
            case NOT_FOUND -> Response.Status.NOT_FOUND.getStatusCode();
            case CONFLICT -> Response.Status.CONFLICT.getStatusCode();
            case FORBIDDEN -> Response.Status.FORBIDDEN.getStatusCode();
        };
    }
}
