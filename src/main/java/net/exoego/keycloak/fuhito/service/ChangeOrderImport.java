package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.ImportDocument.ChangeOrderRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * The change orders of one document. A row is matched by source and external ID, or by ID. A row of a stored order
 * changes the values that it gives. The announcement is a fact of its own and is not part of a row.
 */
final class ChangeOrderImport {

    static final String CHANGE_ORDERS = "changeOrders";

    private final ChangeOrderService orders;
    private final UserDirectory users;
    private final String effectiveDate;
    private final String defaultSource;
    private final ImportReport report;
    private final ImportMatch match;
    private final Runnable rowDone;

    ChangeOrderImport(ChangeOrderService orders, UserDirectory users, ExternalIdService externalIds,
            String effectiveDate, String defaultSource, ImportReport report, Runnable rowDone) {
        this.rowDone = rowDone;
        this.orders = orders;
        this.users = users;
        this.effectiveDate = effectiveDate;
        this.defaultSource = defaultSource;
        this.report = report;
        this.match = new ImportMatch(externalIds, ExternalId.Kind.CHANGE_ORDER);
    }

    void run(List<ChangeOrderRow> rows) {
        report.startList(CHANGE_ORDERS);
        for (int i = 0; i < rows.size(); i++) {
            try {
                report.count(CHANGE_ORDERS, apply(EmptyValues.checked(rows.get(i))));
            } catch (FuhitoException e) {
                report.error(CHANGE_ORDERS, i, e);
            }
            rowDone.run();
        }
    }

    private Outcome apply(ChangeOrderRow row) {
        String source = Checks.code("source", row.source() == null ? defaultSource : row.source());
        Optional<ChangeOrder> stored = match.stored(source, row.id(), row.externalId(),
                id -> orders.find(id).isPresent()).map(orders::get);
        String approver = userId(row.approverUserId(), row.approverUsername(), "approverUserId");
        String requester = userId(row.requesterUserId(), row.requesterUsername(), "requesterUserId");
        if (stored.isEmpty()) {
            ChangeOrderValues values = new ChangeOrderValues(row.number(), row.kind(), row.title(), row.note(),
                    row.orderDate(), row.effectiveDate() == null ? effectiveDate : row.effectiveDate(), approver,
                    requester, row.externalReference());
            report.write(new Write.OfChangeOrder(true, match.create(source, row.externalId(),
                    () -> orders.create(values), ChangeOrder::id)));
            return Outcome.CREATED;
        }
        ChangeOrder before = stored.get();
        // A row that leaves out both keys of a user keeps the stored user. An effective date that the row clears is
        // the one of the import, as for a new order.
        boolean keepsApprover = !row.gives("approverUserId", row.approverUserId())
                && !row.gives("approverUsername", row.approverUsername());
        boolean keepsRequester = !row.gives("requesterUserId", row.requesterUserId())
                && !row.gives("requesterUsername", row.requesterUsername());
        String date = row.or("effectiveDate", row.effectiveDate(), before.effectiveDate().toString());
        ChangeOrderValues values = new ChangeOrderValues(row.or("number", row.number(), before.number()),
                row.or("kind", row.kind(), before.kind()), row.or("title", row.title(), before.title()),
                row.or("note", row.note(), before.note()),
                row.or("orderDate", row.orderDate(), Objects.toString(before.orderDate(), null)),
                date == null ? effectiveDate : date,
                keepsApprover ? before.approverUserId() : approver,
                keepsRequester ? before.requesterUserId() : requester,
                row.or("externalReference", row.externalReference(), before.externalReference()));
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
