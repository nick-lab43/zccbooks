package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organisations/{organisationId}/finance-deposit-settlement-mappings")
public class FinancialDepositSettlementMappingController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;
    private final DepositSettlementLedgerJournalService journals;

    public FinancialDepositSettlementMappingController(JdbcTemplate jdbc, AuthorizationService authorization,
            AdminAuditService audit, DepositSettlementLedgerJournalService journals) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.journals = journals;
    }

    @GetMapping
    public MappingOptions options(@PathVariable UUID organisationId) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_VIEW", organisationId);
        List<AccountOption> accounts = jdbc.query("""
            SELECT id, account_code, account_name, active
            FROM finance_accounts
            WHERE organisation_id = ? AND currency = 'ZAR' AND account_type = 'ASSET'
            ORDER BY account_code
            """, (rs, n) -> new AccountOption(rs.getObject("id", UUID.class).toString(),
                rs.getString("account_code"), rs.getString("account_name"), rs.getBoolean("active")),
            organisationId);
        List<MappingOption> mappings = jdbc.query("""
            SELECT m.id, m.bank_name, m.bank_account_id, ba.account_code AS bank_code,
                   ba.account_name AS bank_account_name, m.cash_account_id,
                   ca.account_code AS cash_code, ca.account_name AS cash_account_name
            FROM finance_deposit_settlement_mappings m
            JOIN finance_accounts ba ON ba.id = m.bank_account_id
            JOIN finance_accounts ca ON ca.id = m.cash_account_id
            WHERE m.organisation_id = ? ORDER BY m.bank_name
            """, (rs, n) -> new MappingOption(rs.getObject("id", UUID.class).toString(),
                rs.getString("bank_name"), rs.getObject("bank_account_id", UUID.class).toString(),
                rs.getString("bank_code"), rs.getString("bank_account_name"),
                rs.getObject("cash_account_id", UUID.class).toString(),
                rs.getString("cash_code"), rs.getString("cash_account_name")), organisationId);
        return new MappingOptions(accounts, mappings);
    }

    @PutMapping
    @Transactional
    public ResponseEntity<Void> save(@PathVariable UUID organisationId,
            @Valid @RequestBody SaveMapping request) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_CREATE", organisationId);
        String bankName = request.bankName().trim();
        if (request.bankAccountId().equals(request.cashAccountId())) {
            throw new IllegalArgumentException("Bank and cash accounts must be different.");
        }
        Integer valid = jdbc.queryForObject("""
            SELECT COUNT(*) FROM finance_accounts
            WHERE organisation_id = ? AND currency = 'ZAR' AND account_type = 'ASSET'
              AND active = TRUE AND id IN (?, ?)
            """, Integer.class, organisationId, request.bankAccountId(), request.cashAccountId());
        if (valid == null || valid != 2) {
            throw new IllegalArgumentException("Select two different active ZAR asset accounts belonging to this organisation.");
        }
        UUID mappingId = jdbc.queryForObject("""
            INSERT INTO finance_deposit_settlement_mappings
                (id, organisation_id, bank_name, currency, bank_account_id, cash_account_id, created_by)
            VALUES (?, ?, ?, 'ZAR', ?, ?, ?)
            ON CONFLICT (organisation_id, LOWER(bank_name), currency)
            DO UPDATE SET bank_account_id = EXCLUDED.bank_account_id,
                          cash_account_id = EXCLUDED.cash_account_id,
                          updated_at = CURRENT_TIMESTAMP
            RETURNING id
            """, UUID.class, UUID.randomUUID(), organisationId, bankName,
            request.bankAccountId(), request.cashAccountId(), actorId());
        audit.record("FINANCE_DEPOSIT_SETTLEMENT_MAPPING_SAVED", "FINANCE_DEPOSIT_SETTLEMENT_MAPPING",
            mappingId, Map.of("organisationId", organisationId.toString(), "bankName", bankName,
                "bankAccountId", request.bankAccountId().toString(),
                "cashAccountId", request.cashAccountId().toString()));
        journals.retryForBankMapping(organisationId, bankName);
        return ResponseEntity.noContent().build();
    }

    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    public record AccountOption(String id, String code, String name, boolean active) {}
    public record MappingOption(String id, String bankName, String bankAccountId, String bankAccountCode,
        String bankAccountName, String cashAccountId, String cashAccountCode, String cashAccountName) {}
    public record MappingOptions(List<AccountOption> accounts, List<MappingOption> mappings) {}
    public record SaveMapping(@NotBlank @Size(max = 120) String bankName,
        @NotNull UUID bankAccountId, @NotNull UUID cashAccountId) {}
}
