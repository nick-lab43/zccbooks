package za.co.zccbooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organisations/{organisationId}/financial-reports")
public class FinancialReportController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public FinancialReportController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/units")
    public List<ReportUnit> units(@PathVariable UUID organisationId) {
        authorization.requireOrganisationOrUnitPermission("FINANCE_VIEW", organisationId);
        String sql = """
            SELECT id, level, code, name FROM organisational_units
            WHERE organisation_id = ? AND active = TRUE
            ORDER BY level, name
            """;
        Object[] parameters = new Object[] { organisationId };
        if (!authorization.hasOrganisationWidePermission("FINANCE_VIEW", organisationId)) {
            sql = """
                WITH RECURSIVE visible_units AS (
                  SELECT a.scope_unit_id AS id
                  FROM admin_role_assignments a
                  JOIN admin_users u ON u.id = a.user_id
                  JOIN admin_role_permissions rp ON rp.role_id = a.role_id
                  WHERE a.scope_type = 'UNIT' AND a.organisation_id = ?
                    AND a.revoked_at IS NULL AND LOWER(u.username) = LOWER(?)
                    AND rp.permission_code = 'FINANCE_VIEW'
                  UNION
                  SELECT child.id FROM organisational_units child
                  JOIN visible_units parent ON child.parent_id = parent.id
                  WHERE child.organisation_id = ?
                )
                SELECT ou.id, ou.level, ou.code, ou.name
                FROM organisational_units ou JOIN visible_units v ON v.id = ou.id
                WHERE ou.organisation_id = ? AND ou.active = TRUE
                ORDER BY ou.level, ou.name
                """;
            parameters = new Object[] { organisationId, authorization.currentUsername(), organisationId, organisationId };
        }
        return jdbc.query(sql, (rs,n) -> new ReportUnit(rs.getObject("id", UUID.class).toString(),
            rs.getString("level"), rs.getString("code"), rs.getString("name")), parameters);
    }

    @GetMapping("/summary")
    public ContributionSummary summary(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        ReportContext context = context(organisationId, scopeUnitId, from, to);
        List<ContributionGroup> groups = jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT c.category_id, cat.name AS category_name, c.stream_id, fs.name AS stream_name,
                   c.currency, COUNT(*) AS contribution_count, SUM(c.amount) AS total_amount
            FROM contributions c
            JOIN visible_units vu ON vu.id = c.scope_unit_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            JOIN financial_streams fs ON fs.id = c.stream_id
            WHERE c.organisation_id = ? AND c.contribution_date BETWEEN ? AND ?
              AND NOT EXISTS (SELECT 1 FROM contribution_reversals r WHERE r.contribution_id = c.id)
            GROUP BY c.category_id, cat.name, c.stream_id, fs.name, c.currency
            ORDER BY fs.name, cat.name, c.currency
            """, (rs,n) -> new ContributionGroup(
                rs.getObject("category_id", UUID.class).toString(), rs.getString("category_name"),
                rs.getObject("stream_id", UUID.class).toString(), rs.getString("stream_name"),
                rs.getString("currency"), rs.getLong("contribution_count"), rs.getBigDecimal("total_amount")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
        List<CurrencyTotal> currencyTotals = totalsByCurrency(groups);
        long count = groups.stream().mapToLong(ContributionGroup::contributionCount).sum();
        return new ContributionSummary(context, count, currencyTotals, groups);
    }

    @GetMapping("/contributions")
    public List<ContributionLine> contributions(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        context(organisationId, scopeUnitId, from, to);
        return jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT c.receipt_number, c.contribution_date, m.first_name, m.last_name,
                   cat.name AS category_name, fs.name AS stream_name, u.name AS recorded_unit,
                   c.payment_method, c.amount, c.currency
            FROM contributions c
            JOIN visible_units vu ON vu.id = c.scope_unit_id
            JOIN members m ON m.id = c.member_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            JOIN financial_streams fs ON fs.id = c.stream_id
            JOIN organisational_units u ON u.id = c.scope_unit_id
            WHERE c.organisation_id = ? AND c.contribution_date BETWEEN ? AND ?
              AND NOT EXISTS (SELECT 1 FROM contribution_reversals r WHERE r.contribution_id = c.id)
            ORDER BY c.contribution_date, c.receipt_sequence
            """, (rs,n) -> new ContributionLine(rs.getString("receipt_number"),
                rs.getDate("contribution_date").toLocalDate(), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("category_name"), rs.getString("stream_name"),
                rs.getString("recorded_unit"), rs.getString("payment_method"),
                rs.getBigDecimal("amount"), rs.getString("currency")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
    }

    @GetMapping("/deposits")
    public List<DepositLine> deposits(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        context(organisationId, scopeUnitId, from, to);
        return jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT d.id, d.deposit_date, d.bank_name, d.bank_reference, d.expected_amount,
                   d.status, u.name AS unit_name, COALESCE(SUM(a.allocated_amount), 0) AS allocated_amount,
                   COUNT(a.id) AS allocation_count,
                   COALESCE((SELECT SUM(e.amount) FROM deposit_reconciliations r
                       JOIN bank_statement_entries e ON e.id = r.statement_entry_id
                       WHERE r.deposit_batch_id = d.id), 0) AS banked_amount,
                   GREATEST(d.expected_amount - COALESCE((SELECT SUM(e.amount)
                       FROM deposit_reconciliations r
                       JOIN bank_statement_entries e ON e.id = r.statement_entry_id
                       WHERE r.deposit_batch_id = d.id), 0), 0) AS outstanding_amount
            FROM deposit_batches d
            JOIN visible_units vu ON vu.id = d.scope_unit_id
            JOIN organisational_units u ON u.id = d.scope_unit_id
            LEFT JOIN deposit_allocations a ON a.deposit_batch_id = d.id
            WHERE d.organisation_id = ? AND d.deposit_date BETWEEN ? AND ?
            GROUP BY d.id, d.deposit_date, d.bank_name, d.bank_reference, d.expected_amount,
                     d.status, u.name
            ORDER BY d.deposit_date, d.id
            """, (rs,n) -> new DepositLine(rs.getObject("id", UUID.class).toString(),
                rs.getDate("deposit_date").toLocalDate(), rs.getString("unit_name"),
                rs.getString("bank_name"), rs.getString("bank_reference"), rs.getBigDecimal("expected_amount"),
                rs.getString("status"), rs.getBigDecimal("allocated_amount"), rs.getLong("allocation_count"),
                rs.getBigDecimal("banked_amount"), rs.getBigDecimal("outstanding_amount")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
    }

    @GetMapping("/expenses")
    public List<ExpenseLine> expenses(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        context(organisationId, scopeUnitId, from, to);
        return jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT e.id, e.expense_date, u.name AS unit_name, c.name AS category_name,
                   e.payee, e.description, e.reference, e.amount, e.currency, e.status,
                   e.payment_reference, e.review_note
            FROM expenditures e
            JOIN visible_units vu ON vu.id = e.scope_unit_id
            JOIN organisational_units u ON u.id = e.scope_unit_id
            JOIN expenditure_categories c ON c.id = e.category_id
            WHERE e.organisation_id = ? AND e.expense_date BETWEEN ? AND ?
            ORDER BY e.expense_date, e.created_at
            """, (rs, n) -> new ExpenseLine(rs.getObject("id", UUID.class).toString(),
                rs.getDate("expense_date").toLocalDate(), rs.getString("unit_name"),
                rs.getString("category_name"), rs.getString("payee"), rs.getString("description"),
                rs.getString("reference"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("status"), rs.getString("payment_reference"), rs.getString("review_note")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
    }

    @GetMapping("/income-expenditure")
    public IncomeExpenditureSummary incomeExpenditure(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        ReportContext reportContext = context(organisationId, scopeUnitId, from, to);
        Map<String, BigDecimal> income = new TreeMap<>();
        jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT currency, SUM(amount) AS total
            FROM contributions c JOIN visible_units vu ON vu.id = c.scope_unit_id
            WHERE c.organisation_id = ? AND c.contribution_date BETWEEN ? AND ?
              AND NOT EXISTS (SELECT 1 FROM contribution_reversals r WHERE r.contribution_id = c.id)
            GROUP BY currency
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                income.put(rs.getString("currency"), rs.getBigDecimal("total"));
            }, scopeUnitId, organisationId, organisationId, organisationId, from, to);
        Map<String, BigDecimal> expenditure = new TreeMap<>();
        jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT currency, SUM(amount) AS total
            FROM expenditures e JOIN visible_units vu ON vu.id = e.scope_unit_id
            WHERE e.organisation_id = ? AND e.expense_date BETWEEN ? AND ?
              AND e.status IN ('APPROVED', 'PAID')
            GROUP BY currency
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                expenditure.put(rs.getString("currency"), rs.getBigDecimal("total"));
            }, scopeUnitId, organisationId, organisationId, organisationId, from, to);
        List<CurrencyPosition> positions = java.util.stream.Stream.concat(income.keySet().stream(), expenditure.keySet().stream())
            .distinct().sorted().map(currency -> {
                BigDecimal received = income.getOrDefault(currency, BigDecimal.ZERO);
                BigDecimal spent = expenditure.getOrDefault(currency, BigDecimal.ZERO);
                return new CurrencyPosition(currency, received, spent, received.subtract(spent));
            }).toList();
        return new IncomeExpenditureSummary(reportContext, positions);
    }


    @GetMapping("/budget-performance")
    public List<BudgetPerformanceLine> budgetPerformance(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        context(organisationId, scopeUnitId, from, to);
        return jdbc.query("""
            WITH RECURSIVE report_units(id) AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN report_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT b.id, b.period_start, b.period_end, u.name AS unit_name,
                   c.code AS category_code, c.name AS category_name,
                   b.budget_amount, b.currency,
                   COALESCE((
                     WITH RECURSIVE budget_scope(id) AS (
                       SELECT b.unit_id
                       UNION ALL
                       SELECT child.id FROM organisational_units child
                       JOIN budget_scope parent ON child.parent_id = parent.id
                       WHERE child.organisation_id = b.organisation_id
                     )
                     SELECT SUM(e.amount) FROM expenditures e
                     JOIN budget_scope bs ON bs.id = e.scope_unit_id
                     WHERE e.organisation_id = b.organisation_id
                       AND e.category_id = b.category_id AND e.currency = b.currency
                       AND e.expense_date BETWEEN b.period_start AND b.period_end
                       AND e.status IN ('APPROVED', 'PAID')
                   ), 0.00::numeric) AS actual_amount
            FROM expenditure_budgets b
            JOIN report_units ru ON ru.id = b.unit_id
            JOIN organisational_units u ON u.id = b.unit_id
            JOIN expenditure_categories c ON c.id = b.category_id
            WHERE b.organisation_id = ?
              AND b.period_start >= ? AND b.period_end <= ?
            ORDER BY b.period_start, u.name, c.name, b.currency
            """, (rs, n) -> {
                BigDecimal budget = rs.getBigDecimal("budget_amount");
                BigDecimal actual = rs.getBigDecimal("actual_amount");
                return new BudgetPerformanceLine(rs.getObject("id", UUID.class).toString(),
                    rs.getDate("period_start").toLocalDate(), rs.getDate("period_end").toLocalDate(),
                    rs.getString("unit_name"), rs.getString("category_code"), rs.getString("category_name"),
                    budget, actual, budget.subtract(actual),
                    actual.multiply(new BigDecimal("100")).divide(budget, 2, java.math.RoundingMode.HALF_UP),
                    rs.getString("currency"));
            }, scopeUnitId, organisationId, organisationId, organisationId, from, to);
    }

    @GetMapping("/exceptions")
    public List<ExceptionLine> exceptions(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        context(organisationId, scopeUnitId, from, to);
        return jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT e.id, e.exception_type, e.status, e.summary, e.resolution_note,
                   e.created_at, u.name AS unit_name
            FROM reconciliation_exceptions e
            JOIN visible_units vu ON vu.id = e.scope_unit_id
            JOIN organisational_units u ON u.id = e.scope_unit_id
            WHERE e.organisation_id = ? AND e.created_at >= ?::date
              AND e.created_at < (?::date + INTERVAL '1 day')
            ORDER BY e.created_at DESC
            """, (rs,n) -> new ExceptionLine(rs.getObject("id", UUID.class).toString(),
                rs.getString("exception_type"), rs.getString("status"), rs.getString("summary"),
                rs.getString("resolution_note"), rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("unit_name")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
    }

    @GetMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> export(@PathVariable UUID organisationId,
            @RequestParam UUID scopeUnitId, @RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "CONTRIBUTION_SUMMARY") String type) throws IOException {
        ReportContext context = context(organisationId, scopeUnitId, from, to);
        if (!List.of("CONTRIBUTION_SUMMARY", "CONTRIBUTION_REGISTER", "DEPOSIT_REGISTER", "EXPENDITURE_REGISTER", "INCOME_EXPENDITURE", "BUDGET_PERFORMANCE", "EXCEPTION_REGISTER").contains(type)) {
            throw new IllegalArgumentException("Unsupported report type.");
        }
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun title = heading.createRun();
            title.setBold(true); title.setFontSize(18); title.setText("ZION CHRISTIAN CHURCH");
            XWPFParagraph subtitle = document.createParagraph();
            subtitle.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun subtitleRun = subtitle.createRun();
            subtitleRun.setBold(true); subtitleRun.setFontSize(14);
            subtitleRun.setText(reportTitle(type));
            document.createParagraph().createRun().setText("Geographical scope: " + context.unitName());
            document.createParagraph().createRun().setText("Reporting period: " + from + " to " + to);
            document.createParagraph().createRun().setText("Generated: " + OffsetDateTime.now());
            document.createParagraph().createRun().setText("Generated by: " + authorization.currentUsername());
            document.createParagraph().createRun().setText("CONFIDENTIAL — FOR AUTHORISED CHURCH ADMINISTRATION");
            document.createParagraph();

            if (type.equals("CONTRIBUTION_SUMMARY")) {
                ContributionSummary report = summary(organisationId, scopeUnitId, from, to);
                XWPFTable table = document.createTable(1, 5);
                setRow(table.getRow(0), "Financial stream", "Category", "Transactions", "Currency", "Total");
                for (ContributionGroup item : report.groups()) {
                    setRow(table.createRow(), item.streamName(), item.categoryName(),
                        Long.toString(item.contributionCount()), item.currency(), item.totalAmount().toPlainString());
                }
                for (CurrencyTotal total : report.currencyTotals()) {
                    XWPFParagraph totalParagraph = document.createParagraph();
                    totalParagraph.createRun().setBold(true);
                    totalParagraph.createRun().setText("Total contributions (" + total.currency() + "): "
                        + total.totalAmount().toPlainString());
                }
                XWPFParagraph countParagraph = document.createParagraph();
                countParagraph.createRun().setText("Transactions: " + report.contributionCount());
            } else if (type.equals("CONTRIBUTION_REGISTER")) {
                XWPFTable table = document.createTable(1, 8);
                setRow(table.getRow(0), "Date", "Receipt", "Contributor", "Category", "Stream", "Payment", "Currency", "Amount");
                for (ContributionLine item : contributions(organisationId, scopeUnitId, from, to)) {
                    setRow(table.createRow(), item.contributionDate().toString(), item.receiptNumber(),
                        item.firstName() + " " + item.lastName(), item.categoryName(), item.streamName(),
                        item.paymentMethod(), item.currency(), item.amount().toPlainString());
                }
            } else if (type.equals("DEPOSIT_REGISTER")) {
                XWPFTable table = document.createTable(1, 9);
                setRow(table.getRow(0), "Date", "Unit", "Bank", "Reference", "Expected",
                    "Allocated", "Banked", "Outstanding", "Status");
                for (DepositLine item : deposits(organisationId, scopeUnitId, from, to)) {
                    setRow(table.createRow(), item.depositDate().toString(), item.unitName(), item.bankName(),
                        item.bankReference(), item.expectedAmount().toPlainString(),
                        item.allocatedAmount().toPlainString(), item.bankedAmount().toPlainString(),
                        item.outstandingAmount().toPlainString(), item.status());
                }
            } else if (type.equals("EXPENDITURE_REGISTER")) {
                XWPFTable table = document.createTable(1, 9);
                setRow(table.getRow(0), "Date", "Unit", "Category", "Payee", "Description",
                    "Reference", "Amount", "Currency", "Status");
                for (ExpenseLine item : expenses(organisationId, scopeUnitId, from, to)) {
                    setRow(table.createRow(), item.expenseDate().toString(), item.unitName(), item.categoryName(),
                        item.payee(), item.description(), item.reference(), item.amount().toPlainString(),
                        item.currency(), item.status());
                }
            } else if (type.equals("BUDGET_PERFORMANCE")) {
                List<BudgetPerformanceLine> lines = budgetPerformance(organisationId, scopeUnitId, from, to);
                XWPFTable table = document.createTable(1, 10);
                setRow(table.getRow(0), "Period start", "Period end", "Unit", "Category", "Budget",
                    "Actual", "Variance", "Utilisation", "Currency", "Status");
                for (BudgetPerformanceLine item : lines) {
                    setRow(table.createRow(), item.periodStart().toString(), item.periodEnd().toString(),
                        item.unitName(), item.categoryName(), item.budgetAmount().toPlainString(),
                        item.actualAmount().toPlainString(), item.remainingAmount().toPlainString(),
                        item.utilisationPercentage().toPlainString() + "%",
                        item.currency(), item.remainingAmount().signum() < 0 ? "OVER BUDGET" : "WITHIN BUDGET");
                }
            } else if (type.equals("INCOME_EXPENDITURE")) {
                IncomeExpenditureSummary report = incomeExpenditure(organisationId, scopeUnitId, from, to);
                XWPFTable table = document.createTable(1, 4);
                setRow(table.getRow(0), "Currency", "Income", "Approved expenditure", "Net position");
                for (CurrencyPosition item : report.positions()) {
                    setRow(table.createRow(), item.currency(), item.income().toPlainString(),
                        item.expenditure().toPlainString(), item.netPosition().toPlainString());
                }
            } else {
                XWPFTable table = document.createTable(1, 5);
                setRow(table.getRow(0), "Date", "Unit", "Type", "Status", "Summary");
                for (ExceptionLine item : exceptions(organisationId, scopeUnitId, from, to)) {
                    setRow(table.createRow(), item.createdAt(), item.unitName(), item.exceptionType(),
                        item.status(), item.summary());
                }
            }
            document.write(output);
            byte[] bytes = output.toByteArray();
            audit.record("FINANCIAL_REPORT_GENERATED", "FINANCIAL_REPORT", UUID.randomUUID(),
                Map.of("organisationId", organisationId.toString(), "scopeUnitId", scopeUnitId.toString(),
                    "type", type, "from", from.toString(), "to", to.toString()));
            String filename = "zcc-" + type.toLowerCase().replace('_', '-') + "-" + from + "-to-" + to + ".docx";
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"").contentType(
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
        }
    }

    @GetMapping(value = "/export/pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId,
            @RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "CONTRIBUTION_SUMMARY") String type) throws IOException {
        ReportContext context = context(organisationId, scopeUnitId, from, to);
        if (!List.of("CONTRIBUTION_SUMMARY", "CONTRIBUTION_REGISTER", "DEPOSIT_REGISTER",
                "EXPENDITURE_REGISTER", "INCOME_EXPENDITURE", "BUDGET_PERFORMANCE", "EXCEPTION_REGISTER").contains(type))
            throw new IllegalArgumentException("Unsupported report type.");

        List<String> headers;
        List<String[]> rows = new java.util.ArrayList<>();
        if (type.equals("CONTRIBUTION_SUMMARY")) {
            headers = List.of("Financial stream", "Category", "Transactions", "Currency", "Total");
            ContributionSummary report = summary(organisationId, scopeUnitId, from, to);
            for (ContributionGroup item : report.groups())
                rows.add(new String[] { item.streamName(), item.categoryName(), Long.toString(item.contributionCount()),
                    item.currency(), item.totalAmount().toPlainString() });
            for (CurrencyTotal total : report.currencyTotals())
                rows.add(new String[] { "TOTAL CONTRIBUTIONS", "", "", total.currency(), total.totalAmount().toPlainString() });
            rows.add(new String[] { "TRANSACTIONS", Long.toString(report.contributionCount()), "", "", "" });
        } else if (type.equals("CONTRIBUTION_REGISTER")) {
            headers = List.of("Date", "Receipt", "Contributor", "Category", "Stream", "Payment", "Currency", "Amount");
            for (ContributionLine item : contributions(organisationId, scopeUnitId, from, to))
                rows.add(new String[] { item.contributionDate().toString(), item.receiptNumber(),
                    item.firstName() + " " + item.lastName(), item.categoryName(), item.streamName(),
                    item.paymentMethod(), item.currency(), item.amount().toPlainString() });
        } else if (type.equals("DEPOSIT_REGISTER")) {
            headers = List.of("Date", "Unit", "Bank", "Reference", "Expected", "Allocated", "Banked", "Outstanding", "Status");
            for (DepositLine item : deposits(organisationId, scopeUnitId, from, to))
                rows.add(new String[] { item.depositDate().toString(), item.unitName(), item.bankName(),
                    item.bankReference(), item.expectedAmount().toPlainString(), item.allocatedAmount().toPlainString(),
                    item.bankedAmount().toPlainString(), item.outstandingAmount().toPlainString(), item.status() });
        } else if (type.equals("EXPENDITURE_REGISTER")) {
            headers = List.of("Date", "Unit", "Category", "Payee", "Description", "Reference", "Amount", "Currency", "Status");
            for (ExpenseLine item : expenses(organisationId, scopeUnitId, from, to))
                rows.add(new String[] { item.expenseDate().toString(), item.unitName(), item.categoryName(),
                    item.payee(), item.description(), item.reference(), item.amount().toPlainString(),
                    item.currency(), item.status() });
        } else if (type.equals("BUDGET_PERFORMANCE")) {
            headers = List.of("Period start", "Period end", "Unit", "Category", "Budget", "Actual",
                "Variance", "Utilisation", "Currency", "Status");
            for (BudgetPerformanceLine item : budgetPerformance(organisationId, scopeUnitId, from, to))
                rows.add(new String[] { item.periodStart().toString(), item.periodEnd().toString(), item.unitName(),
                    item.categoryName(), item.budgetAmount().toPlainString(), item.actualAmount().toPlainString(),
                    item.remainingAmount().toPlainString(), item.utilisationPercentage().toPlainString() + "%",
                    item.currency(), item.remainingAmount().signum() < 0 ? "OVER BUDGET" : "WITHIN BUDGET" });
        } else if (type.equals("INCOME_EXPENDITURE")) {
            headers = List.of("Currency", "Income", "Approved expenditure", "Net position");
            for (CurrencyPosition item : incomeExpenditure(organisationId, scopeUnitId, from, to).positions())
                rows.add(new String[] { item.currency(), item.income().toPlainString(),
                    item.expenditure().toPlainString(), item.netPosition().toPlainString() });
        } else {
            headers = List.of("Date", "Unit", "Type", "Status", "Summary");
            for (ExceptionLine item : exceptions(organisationId, scopeUnitId, from, to))
                rows.add(new String[] { item.createdAt(), item.unitName(), item.exceptionType(), item.status(), item.summary() });
        }

        byte[] bytes = ReportPdfWriter.create(reportTitle(type), context.unitName(), from.toString(), to.toString(),
            authorization.currentUsername(), headers, rows);
        audit.record("FINANCIAL_REPORT_GENERATED", "FINANCIAL_REPORT", UUID.randomUUID(),
            Map.of("organisationId", organisationId.toString(), "scopeUnitId", scopeUnitId.toString(),
                "type", type, "format", "PDF", "from", from.toString(), "to", to.toString()));
        String filename = "zcc-" + type.toLowerCase().replace('_', '-') + "-" + from + "-to-" + to + ".pdf";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF).body(bytes);
    }

    private ReportContext context(UUID organisationId, UUID scopeUnitId, LocalDate from, LocalDate to) {
        authorization.requireUnitPermission("FINANCE_VIEW", scopeUnitId);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("A valid reporting date range is required.");
        }
        return jdbc.query("""
            SELECT id, name, level FROM organisational_units
            WHERE id = ? AND organisation_id = ?
            """, (rs,n) -> new ReportContext(rs.getObject("id", UUID.class).toString(),
                rs.getString("name"), rs.getString("level"), from, to),
            scopeUnitId, organisationId).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Reporting scope was not found in this organisation."));
    }

    static List<CurrencyTotal> totalsByCurrency(List<ContributionGroup> groups) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        groups.forEach(group -> totals.merge(group.currency(), group.totalAmount(), BigDecimal::add));
        return totals.entrySet().stream()
            .map(entry -> new CurrencyTotal(entry.getKey(), entry.getValue())).toList();
    }

    private static String reportTitle(String type) {
        return switch (type) {
            case "CONTRIBUTION_REGISTER" -> "CONTRIBUTION REGISTER";
            case "DEPOSIT_REGISTER" -> "DEPOSIT REGISTER";
            case "EXPENDITURE_REGISTER" -> "EXPENDITURE REGISTER";
            case "INCOME_EXPENDITURE" -> "INCOME AND EXPENDITURE SUMMARY";
            case "BUDGET_PERFORMANCE" -> "EXPENDITURE BUDGET PERFORMANCE";
            case "EXCEPTION_REGISTER" -> "RECONCILIATION EXCEPTION REGISTER";
            default -> "CONTRIBUTION SUMMARY";
        };
    }

    private static void setRow(XWPFTableRow row, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i >= row.getTableCells().size()) row.addNewTableCell();
            row.getCell(i).setText(values[i] == null ? "" : values[i]);
        }
    }

    public record ReportUnit(String id, String level, String code, String name) {}
    public record ReportContext(String unitId, String unitName, String unitLevel, LocalDate from, LocalDate to) {}
    public record ContributionGroup(String categoryId, String categoryName, String streamId, String streamName,
        String currency, long contributionCount, BigDecimal totalAmount) {}
    public record CurrencyTotal(String currency, BigDecimal totalAmount) {}
    public record ContributionSummary(ReportContext context, long contributionCount,
        List<CurrencyTotal> currencyTotals, List<ContributionGroup> groups) {}
    public record ContributionLine(String receiptNumber, LocalDate contributionDate, String firstName,
        String lastName, String categoryName, String streamName, String recordedUnit, String paymentMethod,
        BigDecimal amount, String currency) {}
    public record DepositLine(String id, LocalDate depositDate, String unitName, String bankName,
        String bankReference, BigDecimal expectedAmount, String status, BigDecimal allocatedAmount,
        long allocationCount, BigDecimal bankedAmount, BigDecimal outstandingAmount) {}
    public record ExpenseLine(String id, LocalDate expenseDate, String unitName, String categoryName,
        String payee, String description, String reference, BigDecimal amount, String currency,
        String status, String paymentReference, String reviewNote) {}
    public record BudgetPerformanceLine(String id, LocalDate periodStart, LocalDate periodEnd, String unitName,
        String categoryCode, String categoryName, BigDecimal budgetAmount, BigDecimal actualAmount,
        BigDecimal remainingAmount, BigDecimal utilisationPercentage, String currency) {}
    public record CurrencyPosition(String currency, BigDecimal income, BigDecimal expenditure, BigDecimal netPosition) {}
    public record IncomeExpenditureSummary(ReportContext context, List<CurrencyPosition> positions) {}
    public record ExceptionLine(String id, String exceptionType, String status, String summary,
        String resolutionNote, String createdAt, String unitName) {}
}
