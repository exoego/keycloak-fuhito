package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Checks the keys by which a row of an import names stored things, before the import reads stored data by them. A
 * key in a form that a write never stores finds nothing, so the row would fail with an error about the thing that it
 * did not find. The keys are {@code source}, the {@code externalId} of the row, and each other field that names a
 * thing by its ID in a system, such as {@code parentExternalId} or {@code successorExternalIds}.
 */
final class RowKeys {

    private RowKeys() {
    }

    /**
     * Requires the keys of a row in the form that a write takes.
     *
     * @param row the row
     * @throws FuhitoException when {@code source} is no code, the {@code externalId} of the row is bad, or an ID of
     *         a system that the row names has spaces around it. The error names the field.
     */
    static void require(Record row) {
        for (RecordComponent component : row.getClass().getRecordComponents()) {
            String field = component.getName();
            Object value = EmptyValues.valueOf(component, row);
            if (field.equals("source")) {
                Checks.optionalCode(field, (String) value);
            } else if (field.equals("externalId")) {
                ExternalIdService.value((String) value);
            } else if (field.endsWith("ExternalId")) {
                Checks.unpadded(field, (String) value);
            } else if (field.endsWith("ExternalIds") && value != null) {
                ((List<?>) value).forEach(item -> Checks.unpadded(field, (String) item));
            }
        }
    }
}
