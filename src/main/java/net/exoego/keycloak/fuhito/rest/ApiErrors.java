package net.exoego.keycloak.fuhito.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The error responses that every operation can give, for the OpenAPI document.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@APIResponse(responseCode = "401", ref = "Unauthorized")
@APIResponse(responseCode = "403", ref = "Forbidden")
@APIResponse(responseCode = "409", ref = "Conflict")
@interface ApiErrors {
}
