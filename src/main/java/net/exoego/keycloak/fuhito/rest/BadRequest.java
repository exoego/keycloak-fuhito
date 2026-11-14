package net.exoego.keycloak.fuhito.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The response to a bad value in the request, for the OpenAPI document.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@APIResponse(responseCode = "400", ref = "BadRequest")
@interface BadRequest {
}
