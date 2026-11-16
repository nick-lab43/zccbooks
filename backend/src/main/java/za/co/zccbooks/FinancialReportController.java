package za.co.zccbooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
                   COUNT(*) AS contribution_count, SUM(c.amount) AS total_amount
            FROM contributions c
            JOIN visible_units vu ON vu.id = c.scope_unit_id
            JOIN contribution_categories cat ON cat.id = c.category_id
            JOIN financial_streams fs ON fs.id = c.stream_id
            WHERE c.organisation_id = ? AND c.contribution_date BETWEEN ? AND ?
            GROUP BY c.category_id, cat.name, c.stream_id, fs.name
            ORDER BY fs.name, cat.name
            """, (rs,n) -> new ContributionGroup(
                rs.getObject("category_id", UUID.class).toString(), rs.getString("category_name"),
                rs.getObject("stream_id", UUID.class).toString(), rs.getString("stream_name"),
                rs.getLong("contribution_count"), rs.getBigDecimal("total_amount")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
        BigDecimal total = groups.stream().map(ContributionGroup::totalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = groups.stream().mapToLong(ContributionGroup::contributionCount).sum();
        return new ContributionSummary(context, count, total, groups);
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
                   COUNT(a.id) AS allocation_count
            FROM deposit_batches d
            JOIN visible_units vu ON vu.id = d.scope_unit_id
            JOIN organisational_units u ON u.id = d.scope_unit_id
            LEFT JOIN deposit_allocations a ON a.deposit_batch_id = d.id
            WHERE d.organisation_id = ? AND d.deposit_date BETWEEN ? AND ?
            GROUP BY d.id, d.deposit_date, d.bank_name, d.bank_reference, d.expected_amount,
                     d.status, u.name
            ORDER BY d.deposit_date, d.created_at
            """, (rs,n) -> new DepositLine(rs.getObject("id", UUID.class).toString(),
                rs.getDate("deposit_date").toLocalDate(), rs.getString("unit_name"),
                rs.getString("bank_name"), rs.getString("bank_reference"), rs.getBigDecimal("expected_amount"),
                rs.getString("status"), rs.getBigDecimal("allocated_amount"), rs.getLong("allocation_count")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
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
        if (!List.of("CONTRIBUTION_SUMMARY", "CONTRIBUTION_REGISTER", "DEPOSIT_REGISTER", "EXCEPTION_REGISTER").contains(type)) {
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
                XWPFTable table = document.createTable(1, 4);
                setRow(table.getRow(0), "Financial stream", "Category", "Transactions", "Total (ZAR)");
                for (ContributionGroup item : report.groups()) {
                    setRow(table.createRow(), item.streamName(), item.categoryName(),
                        Long.toString(item.contributionCount()), item.totalAmount().toPlainString());
                }
                XWPFParagraph total = document.createParagraph();
                total.createRun().setBold(true);
                total.createRun().setText("Total contributions: ZAR " + report.totalAmount().toPlainString()
                    + " across " + report.contributionCount() + " transactions");
            } else if (type.equals("CONTRIBUTION_REGISTER")) {
                XWPFTable table = document.createTable(1, 7);
                setRow(table.getRow(0), "Date", "Receipt", "Contributor", "Category", "Stream", "Payment", "Amount");
                for (ContributionLine item : contributions(organisationId, scopeUnitId, from, to)) {
                    setRow(table.createRow(), item.contributionDate().toString(), item.receiptNumber(),
                        item.firstName() + " " + item.lastName(), item.categoryName(), item.streamName(),
                        item.paymentMethod(), item.currency() + " " + item.amount().toPlainString());
                }
            } else if (type.equals("DEPOSIT_REGISTER")) {
                XWPFTable table = document.createTable(1, 7);
                setRow(table.getRow(0), "Date", "Unit", "Bank", "Reference", "Expected", "Allocated", "Status");
                for (DepositLine item : deposits(organisationId, scopeUnitId, from, to)) {
                    setRow(table.createRow(), item.depositDate().toString(), item.unitName(), item.bankName(),
                        item.bankReference(), item.expectedAmount().toPlainString(),
                        item.allocatedAmount().toPlainString(), item.status());
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

    private static String reportTitle(String type) {
        return switch (type) {
            case "CONTRIBUTION_REGISTER" -> "CONTRIBUTION REGISTER";
            case "DEPOSIT_REGISTER" -> "DEPOSIT REGISTER";
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

    public record ReportContext(String unitId, String unitName, String unitLevel, LocalDate from, LocalDate to) {}
    public record ContributionGroup(String categoryId, String categoryName, String streamId, String streamName,
        long contributionCount, BigDecimal totalAmount) {}
    public record ContributionSummary(ReportContext context, long contributionCount, BigDecimal totalAmount,
        List<ContributionGroup> groups) {}
    public record ContributionLine(String receiptNumber, LocalDate contributionDate, String firstName,
        String lastName, String categoryName, String streamName, String recordedUnit, String paymentMethod,
        BigDecimal amount, String currency) {}
    public record DepositLine(String id, LocalDate depositDate, String unitName, String bankName,
        String bankReference, BigDecimal expectedAmount, String status, BigDecimal allocatedAmount,
        long allocationCount) {}
    public record ExceptionLine(String id, String exceptionType, String status, String summary,
        String resolutionNote, String createdAt, String unitName) {}
}
