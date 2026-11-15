package net.exoego.keycloak.fuhito.rest;

import java.util.function.Supplier;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Messages;

/**
 * Runs an operation and turns its failure into an error response.
 */
final class Calls {

    private Calls() {
    }

    static <T> T run(RequestContext context, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (FuhitoException e) {
            var body = new ErrorRepresentation(e.code(), e.params(),
                    Messages.format(e.code(), e.params(), context.locale()));
            throw context.failure(Response.status(status(e.reason()))
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(body)
                    .build());
        }
    }

    /**
     * Runs a read of one resource, and sends the ETag of the representation that it returns.
     *
     * @param <T> the type of the representation
     * @param context the request
     * @param operation the read, which returns the whole resource
     * @return the representation
     */
    static <T> T tagged(RequestContext context, Supplier<T> operation) {
        return run(context, () -> {
            T representation = operation.get();
            ETags.send(context, representation);
            return representation;
        });
    }

    /**
     * Runs a read of one resource, and sends the ETag of its whole state.
     *
     * @param <T> the type of the response
     * @param context the request
     * @param state reads the whole state of the resource
     * @param operation the read
     * @return the response
     */
    static <T> T tagged(RequestContext context, Supplier<Object> state, Supplier<T> operation) {
        return run(context, () -> {
            T result = operation.get();
            ETags.send(context, state.get());
            return result;
        });
    }

    /**
     * Runs a write of one resource. With {@code If-Match}, the write goes on only when the resource is in that
     * state. The response has the ETag of the new state.
     *
     * @param <T> the type of the response
     * @param context the request
     * @param state reads the whole state of the resource
     * @param operation the write
     * @return the response
     */
    static <T> T guarded(RequestContext context, Supplier<Object> state, Supplier<T> operation) {
        return run(context, () -> {
            ETags.require(context, state);
            T result = operation.get();
            ETags.send(context, state.get());
            return result;
        });
    }

    /**
     * Runs a write that removes one resource. With {@code If-Match}, it goes on only when the resource is in that
     * state.
     *
     * @param <T> the type of the response
     * @param context the request
     * @param state reads the whole state of the resource
     * @param operation the write
     * @return the response
     */
    static <T> T guardedRemoval(RequestContext context, Supplier<Object> state, Supplier<T> operation) {
        return run(context, () -> {
            ETags.require(context, state);
            return operation.get();
        });
    }

    private static Response.Status status(FuhitoException.Reason reason) {
        return switch (reason) {
            case INVALID -> Response.Status.BAD_REQUEST;
            case NOT_FOUND -> Response.Status.NOT_FOUND;
            case CONFLICT -> Response.Status.CONFLICT;
            case PRECONDITION -> Response.Status.PRECONDITION_FAILED;
        };
    }
}
