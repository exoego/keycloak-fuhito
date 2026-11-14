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

    private static Response.Status status(FuhitoException.Reason reason) {
        return switch (reason) {
            case INVALID -> Response.Status.BAD_REQUEST;
            case NOT_FOUND -> Response.Status.NOT_FOUND;
            case CONFLICT -> Response.Status.CONFLICT;
        };
    }
}
