package net.exoego.keycloak.fuhito.rest;

import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Messages;

/**
 * Runs an operation and turns its failure into an error response.
 */
final class Calls {

    // Jakarta REST has no constant for it.
    private static final int UNPROCESSABLE_CONTENT = 422;

    private Calls() {
    }

    static <T> T run(RequestContext context, Supplier<T> operation) {
        try {
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
     * Runs a write of one stored resource. It goes on only when the resource still has the version that the body
     * names.
     *
     * @param <T> the type of the response
     * @param context the request
     * @param body the body of the request, with the version that the caller read
     * @param current reads the version of the resource that the path names
     * @param operation the write
     * @return the response
     */
    static <T> T guarded(RequestContext context, Versioned body, LongSupplier current, Supplier<T> operation) {
        return run(context, () -> {
            Checks.required("body", body);
            requireTarget(context, body.version(), current);
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
        return run(context, () -> {
            requireTarget(context, versionOf(version), current);
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

    private static void requireTarget(RequestContext context, Long version, LongSupplier current) {
        // A second write with the same version waits here, and then reads the version that the first one wrote.
        context.lockTarget();
        Checks.version(version, current.getAsLong());
    }

    /**
     * Runs a request that may be sent again, for example after a timeout. With the header
     * {@code Idempotency-Key}, a request with the same key, path, and body returns the first response and writes
     * nothing.
     *
     * @param <T> the type of the response
     * @param context the request
     * @param body the body of the request, or null
     * @param type the type of the response
     * @param operation the request, which turns its own failures into responses
     * @return the response
     */
    static <T> T once(RequestContext context, Object body, Class<T> type, Supplier<T> operation) {
        String key = context.header(Idempotency.HEADER);
        if (key == null) {
            return operation.get();
        }
        return run(context, () -> Idempotency.once(context, key, body, type, operation));
    }

    private static int status(FuhitoException.Reason reason) {
        return switch (reason) {
            case INVALID -> Response.Status.BAD_REQUEST.getStatusCode();
            case NOT_FOUND -> Response.Status.NOT_FOUND.getStatusCode();
            case CONFLICT -> Response.Status.CONFLICT.getStatusCode();
            case UNPROCESSABLE -> UNPROCESSABLE_CONTENT;
            case FORBIDDEN -> Response.Status.FORBIDDEN.getStatusCode();
        };
    }
}
