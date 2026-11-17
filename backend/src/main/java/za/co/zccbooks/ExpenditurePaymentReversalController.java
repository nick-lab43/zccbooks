package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ExpenditurePaymentReversalController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;
    private final ExpenditurePaymentLedgerJournalService expenditureLedger;

    public ExpenditurePaymentReversalController(JdbcTemplate jdbc, AuthorizationService authorization,
            AdminAuditService audit, ExpenditurePaymentLedgerJournalService expenditureLedger) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.expenditureLedger = expenditureLedger;
    }

    @PostMapping("/expenditures/{expenditureId}/reverse-payment")
    @Transactional
    public ReversalResult reversePayment(@PathVariable UUID expenditureId,
            @Valid @RequestBody ReverseExpenditurePayment request) {
        ExpenditureSource source = jdbc.query("""
            SELECT id, organisation_id, scope_unit_id, status, amount, currency, payee, payment_reference
            FROM expenditures
            WHERE id = ?
            FOR UPDATE
            """, (rs, n) -> new ExpenditureSource(
                rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                rs.getObject("scope_unit_id", UUID.class), rs.getString("status"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("payee"),
                rs.getString("payment_reference")), expenditureId)
            .stream().findFirst().orElseThrow(() ->
                new ResourceNotFoundException("Expenditure record not found."));

        authorization.requireUnitPermission("FINANCE_REVERSE", source.scopeUnitId());
        if (!"PAID".equals(source.status())) {
            throw new ConflictException("Only paid expenditure records can have their payment reversed.");
        }
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM expenditure_payment_reversals WHERE expenditure_id = ?",
            Integer.class, expenditureId);
        if (existing != null && existing > 0) {
            throw new ConflictException("This expenditure payment has already been reversed.");
        }

        UUID actor = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        UUID reversalId = UUID.randomUUID();
        String reason = request.reason().trim();
        jdbc.update("""
            INSERT INTO expenditure_payment_reversals (id, expenditure_id, reason, reversed_by)
            VALUES (?, ?, ?, ?)
            """, reversalId, expenditureId, reason, actor);

        UUID reversingJournalId = expenditureLedger.createReversalDraft(
            expenditureId, reversalId, actor, reason);

        audit.record("EXPENDITURE_PAYMENT_REVERSED", "EXPENDITURE", expenditureId,
            Map.of("reversalId", reversalId.toString(),
                "amount", source.amount().toPlainString(), "currency", source.currency(),
                "payee", source.payee(), "paymentReference",
                source.paymentReference() == null ? "" : source.paymentReference(),
                "reversingJournalCreated", Boolean.toString(reversingJournalId != null),
                "reason", reason));

        return new ReversalResult(reversalId.toString(), expenditureId.toString(),
            source.amount(), source.currency(), reversingJournalId == null ? null : reversingJournalId.toString(),
            "PAID", "Payment reversal recorded; the original expenditure and payment remain in history.");
    }

    public record ReverseExpenditurePayment(@NotBlank @Size(max = 1000) String reason) {}
    public record ReversalResult(String reversalId, String expenditureId, BigDecimal reversedAmount,
        String currency, String reversingJournalId, String originalStatus, String message) {}
    private record ExpenditureSource(UUID id, UUID organisationId, UUID scopeUnitId,
        String status, BigDecimal amount, String currency, String payee, String paymentReference) {}
}
