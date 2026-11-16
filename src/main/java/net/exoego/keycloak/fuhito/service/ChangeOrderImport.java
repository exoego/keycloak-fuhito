package net.exoego.keycloak.fuhito.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.ImportDocument.ChangeOrderRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * The change orders of one document. A row is matched by number, or by ID. A row of a stored order replaces its
 * values, as {@code PUT} does. The announcement is a fact of its own and is not part of a row.
 */
final class ChangeOrderImport {

    static final String CHANGE_ORDERS = "changeOrders";

    private final ChangeOrderService orders;
    private final UserDirectory users;
    private final String effectiveDate;
    private final ImportReport report;
    private final Set<String> seen = new HashSet<>();

    ChangeOrderImport(ChangeOrderService orders, UserDirectory users, String effectiveDate, ImportReport report) {
        this.orders = orders;
        this.users = users;
        this.effectiveDate = effectiveDate;
        this.report = report;
    }

    void run(List<ChangeOrderRow> rows) {
        report.startList(CHANGE_ORDERS);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(CHANGE_ORDERS, apply(Checks.required("row", rows.get(i))));
            } catch (FuhitoException e) {
                report.error(CHANGE_ORDERS, i, e);
            }
        }
    }

    private Outcome apply(ChangeOrderRow row) {
        if (row.number() == null && row.id() == null) {
            throw FuhitoException.invalid("invalid.required", Map.of("field", "number"));
        }
        if (!seen.add(row.id() == null ? row.number() : "\u0000id\u0000" + row.id())) {
            throw FuhitoException.invalid("import.duplicate_row",
                    Map.of("externalId", Objects.requireNonNullElse(row.number(), row.id())));
        }
        Optional<ChangeOrder> stored = row.id() == null ? orders.ofNumber(row.number())
                : Optional.of(orders.find(row.id())
                        .orElseThrow(() -> FuhitoException.invalid("import.id_unknown", Map.of("id", row.id()))));
        ChangeOrderValues values = new ChangeOrderValues(row.number(), row.kind(), row.title(), row.note(),
                row.orderDate(), row.effectiveDate() == null ? effectiveDate : row.effectiveDate(),
                userId(row.approverUserId(), row.approverUsername(), "approverUserId"),
                userId(row.requesterUserId(), row.requesterUsername(), "requesterUserId"), row.externalReference());
        if (stored.isEmpty()) {
            report.write(new Write.OfChangeOrder(true, orders.create(values)));
            return Outcome.CREATED;
        }
        ChangeOrder before = stored.get();
        boolean same = Objects.equals(before.number(), values.number())
                && Objects.equals(before.kind(), values.kind())
                && before.title().equals(Checks.name("title", values.title()))
                && Objects.equals(before.note(), values.note())
                && Objects.equals(Objects.toString(before.orderDate(), null), values.orderDate())
                && before.effectiveDate().toString().equals(values.effectiveDate())
                && Objects.equals(before.approverUserId(), values.approverUserId())
                && Objects.equals(before.requesterUserId(), values.requesterUserId())
                && Objects.equals(before.externalReference(), values.externalReference());
        if (same) {
            return Outcome.UNCHANGED;
        }
        report.write(new Write.OfChangeOrder(false, orders.update(before.id(), values)));
        return Outcome.CHANGED;
    }

    private String userId(String userId, String username, String field) {
        if (userId != null && username != null) {
            throw FuhitoException.invalid("import.user_twice", Map.of("field", field));
        }
        if (username == null) {
            return userId;
        }
        return users.findIdByUsername(username).orElseThrow(() -> FuhitoException.invalid(
                "import.username_unknown", Map.of("username", username)));
    }
}
