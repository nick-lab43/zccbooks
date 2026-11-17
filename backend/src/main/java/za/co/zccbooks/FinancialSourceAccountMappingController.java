package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organisations/{organisationId}/finance-source-account-mappings")
public class FinancialSourceAccountMappingController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public FinancialSourceAccountMappingController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/options")
    public MappingOptions options(@PathVariable UUID organisationId) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_VIEW", organisationId);
        List<MappingAccount> accounts = jdbc.query("""
            SELECT id, account_code, account_name, account_type, currency, active
            FROM finance_accounts WHERE organisation_id = ?
            ORDER BY currency, account_code
            """, (rs,n) -> new MappingAccount(rs.getObject("id", UUID.class).toString(),
                rs.getString("account_code"), rs.getString("account_name"), rs.getString("account_type"),
                rs.getString("currency"), rs.getBoolean("active")), organisationId);
        List<SourceCategory> contributions = jdbc.query("""
            SELECT id, code, name, active FROM contribution_categories
            WHERE organisation_id = ? ORDER BY name
            """, (rs,n) -> new SourceCategory(rs.getObject("id", UUID.class).toString(),
                rs.getString("code"), rs.getString("name"), rs.getBoolean("active")),
            organisationId);
        List<SourceCategory> expenditures = jdbc.query("""
            SELECT id, code, name, active FROM expenditure_categories
            WHERE organisation_id = ? ORDER BY name
            """, (rs,n) -> new SourceCategory(rs.getObject("id", UUID.class).toString(),
                rs.getString("code"), rs.getString("name"), rs.getBoolean("active")),
            organisationId);
        List<SourceMapping> contributionMappings = jdbc.query("""
            SELECT m.id, m.category_id, c.code AS category_code, c.name AS category_name,
                   m.payment_method, m.currency, m.debit_account_id,
                   da.account_code AS debit_code, da.account_name AS debit_name,
                   m.credit_account_id, ca.account_code AS credit_code, ca.account_name AS credit_name
            FROM finance_contribution_account_mappings m
            JOIN contribution_categories c ON c.id = m.category_id
            JOIN finance_accounts da ON da.id = m.debit_account_id
            JOIN finance_accounts ca ON ca.id = m.credit_account_id
            WHERE m.organisation_id = ?
            ORDER BY c.name, m.payment_method
            """, (rs,n) -> new SourceMapping(rs.getObject("id", UUID.class).toString(),
                "CONTRIBUTION", rs.getObject("category_id", UUID.class).toString(),
                rs.getString("category_code"), rs.getString("category_name"), rs.getString("payment_method"),
                rs.getString("currency"), rs.getObject("debit_account_id", UUID.class).toString(),
                rs.getString("debit_code"), rs.getString("debit_name"),
                rs.getObject("credit_account_id", UUID.class).toString(),
                rs.getString("credit_code"), rs.getString("credit_name")), organisationId);
        List<SourceMapping> expenditureMappings = jdbc.query("""
            SELECT m.id, m.category_id, c.code AS category_code, c.name AS category_name,
                   m.currency, m.debit_account_id, da.account_code AS debit_code,
                   da.account_name AS debit_name, m.credit_account_id,
                   ca.account_code AS credit_code, ca.account_name AS credit_name
            FROM finance_expenditure_account_mappings m
            JOIN expenditure_categories c ON c.id = m.category_id
            JOIN finance_accounts da ON da.id = m.debit_account_id
            JOIN finance_accounts ca ON ca.id = m.credit_account_id
            WHERE m.organisation_id = ?
            ORDER BY c.name
            """, (rs,n) -> new SourceMapping(rs.getObject("id", UUID.class).toString(),
                "EXPENDITURE", rs.getObject("category_id", UUID.class).toString(),
                rs.getString("category_code"), rs.getString("category_name"), null,
                rs.getString("currency"), rs.getObject("debit_account_id", UUID.class).toString(),
                rs.getString("debit_code"), rs.getString("debit_name"),
                rs.getObject("credit_account_id", UUID.class).toString(),
                rs.getString("credit_code"), rs.getString("credit_name")), organisationId);
        return new MappingOptions(accounts, contributions, expenditures, contributionMappings, expenditureMappings);
    }

    @PutMapping
    @Transactional
    public SourceMapping save(@PathVariable UUID organisationId, @Valid @RequestBody SaveSourceMapping request) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_JOURNAL_CREATE", organisationId);
        String sourceType = request.sourceType();
        String currency = request.currency().toUpperCase();
        if (!"ZAR".equals(currency)) {
            throw new IllegalArgumentException("Contribution and expenditure source records currently use ZAR.");
        }
        if (request.debitAccountId().equals(request.creditAccountId())) {
            throw new IllegalArgumentException("Debit and credit accounts must be different.");
        }
        String debitType = "CONTRIBUTION".equals(sourceType) ? "ASSET" : "EXPENSE";
        String creditType = "CONTRIBUTION".equals(sourceType) ? "INCOME" : "ASSET";
        Integer validAccounts = jdbc.queryForObject("""
            SELECT COUNT(*) FROM finance_accounts
            WHERE organisation_id = ? AND currency = ? AND active = TRUE
              AND ((id = ? AND account_type = ?) OR (id = ? AND account_type = ?))
            """, Integer.class, organisationId, currency, request.debitAccountId(), debitType,
            request.creditAccountId(), creditType);
        if (validAccounts == null || validAccounts != 2) {
            throw new IllegalArgumentException("Select active ZAR accounts with the required accounting types: "
                + debitType + " debit and " + creditType + " credit.");
        }

        UUID mappingId;
        if ("CONTRIBUTION".equals(sourceType)) {
            String method = request.paymentMethod();
            if (method == null || !List.of("CASH", "BANK_TRANSFER", "CARD", "MOBILE_MONEY", "OTHER").contains(method)) {
                throw new IllegalArgumentException("Select a supported contribution payment method.");
            }
            if (count("SELECT COUNT(*) FROM contribution_categories WHERE id = ? AND organisation_id = ? AND active = TRUE",
                    request.categoryId(), organisationId) != 1) {
                throw new ResourceNotFoundException("Active contribution category not found in this organisation.");
            }
            mappingId = jdbc.queryForObject("""
                INSERT INTO finance_contribution_account_mappings
                    (id, organisation_id, category_id, payment_method, currency,
                     debit_account_id, credit_account_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organisation_id, category_id, payment_method, currency)
                DO UPDATE SET debit_account_id = EXCLUDED.debit_account_id,
                              credit_account_id = EXCLUDED.credit_account_id,
                              updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, UUID.class, UUID.randomUUID(), organisationId, request.categoryId(), method,
                currency, request.debitAccountId(), request.creditAccountId(), actorId());
        } else if ("EXPENDITURE".equals(sourceType)) {
            if (request.paymentMethod() != null && !request.paymentMethod().isBlank()) {
                throw new IllegalArgumentException("Expenditure mappings do not use a contribution payment method.");
            }
            if (count("SELECT COUNT(*) FROM expenditure_categories WHERE id = ? AND organisation_id = ? AND active = TRUE",
                    request.categoryId(), organisationId) != 1) {
                throw new ResourceNotFoundException("Active expenditure category not found in this organisation.");
            }
            mappingId = jdbc.queryForObject("""
                INSERT INTO finance_expenditure_account_mappings
                    (id, organisation_id, category_id, currency, debit_account_id, credit_account_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organisation_id, category_id, currency)
                DO UPDATE SET debit_account_id = EXCLUDED.debit_account_id,
                              credit_account_id = EXCLUDED.credit_account_id,
                              updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, UUID.class, UUID.randomUUID(), organisationId, request.categoryId(), currency,
                request.debitAccountId(), request.creditAccountId(), actorId());
        } else {
            throw new IllegalArgumentException("Source type must be CONTRIBUTION or EXPENDITURE.");
        }
        audit.record("FINANCE_SOURCE_ACCOUNT_MAPPING_SAVED", "FINANCE_SOURCE_ACCOUNT_MAPPING", mappingId,
            Map.of("organisationId", organisationId.toString(), "sourceType", sourceType,
                "categoryId", request.categoryId().toString(), "currency", currency,
                "debitAccountId", request.debitAccountId().toString(),
                "creditAccountId", request.creditAccountId().toString(),
                "paymentMethod", request.paymentMethod() == null ? "NOT_APPLICABLE" : request.paymentMethod()));
        return options(organisationId).contributionMappings().stream().filter(m -> m.id().equals(mappingId.toString()))
            .findFirst().orElseGet(() -> options(organisationId).expenditureMappings().stream()
                .filter(m -> m.id().equals(mappingId.toString())).findFirst().orElseThrow());
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }
    private UUID actorId() {
        return jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
    }

    public record MappingAccount(String id, String code, String name, String accountType, String currency, boolean active) {}
    public record SourceCategory(String id, String code, String name, boolean active) {}
    public record SourceMapping(String id, String sourceType, String categoryId, String categoryCode,
        String categoryName, String paymentMethod, String currency, String debitAccountId,
        String debitAccountCode, String debitAccountName, String creditAccountId,
        String creditAccountCode, String creditAccountName) {}
    public record MappingOptions(List<MappingAccount> accounts, List<SourceCategory> contributionCategories,
        List<SourceCategory> expenditureCategories, List<SourceMapping> contributionMappings,
        List<SourceMapping> expenditureMappings) {}
    public record SaveSourceMapping(@NotNull UUID categoryId,
        @Pattern(regexp = "CONTRIBUTION|EXPENDITURE") String sourceType,
        String paymentMethod, @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull UUID debitAccountId, @NotNull UUID creditAccountId) {}
}
