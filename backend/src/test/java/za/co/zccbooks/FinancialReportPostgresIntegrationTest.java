package za.co.zccbooks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class FinancialReportPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("zccbooks_test")
            .withUsername("zccbooks")
            .withPassword("zccbooks_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("app.bootstrap-admin.username", () -> "");
        registry.add("app.bootstrap-admin.password", () -> "");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FinancialReportController reports;
    @Autowired AdminAuditService audit;
    @Autowired DepositManagementController depositManagement;
    @Autowired DepositReviewController depositReview;
    @Autowired ContributionController contributionController;
    @Autowired ExpenditureManagementController expenditureManagement;
    @Autowired BudgetManagementController budgetManagement;
    @Autowired DepositEvidenceController depositEvidence;
    @Autowired DepositDocumentController depositDocuments;
    @Autowired ExpenditureEvidenceController expenditureEvidence;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unitListAndAllRegistersStayInsideAssignedNyika() {
        Fixture fixture = fixture();
        authenticate(fixture);

        List<FinancialReportController.ReportUnit> units = reports.units(fixture.organisationId());

        assertTrue(units.stream().anyMatch(unit -> unit.id().equals(fixture.nyikaA().toString())));
        assertTrue(units.stream().anyMatch(unit -> unit.id().equals(fixture.cellA().toString())));
        assertFalse(units.stream().anyMatch(unit -> unit.id().equals(fixture.nyikaB().toString())));
        assertFalse(units.stream().anyMatch(unit -> unit.id().equals(fixture.cellB().toString())));

        FinancialReportController.ContributionSummary summary = reports.summary(
            fixture.organisationId(), fixture.nyikaA(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertEquals(1, summary.contributionCount());
        assertEquals(1, summary.currencyTotals().size());
        assertEquals("ZAR", summary.currencyTotals().get(0).currency());
        assertEquals(new BigDecimal("125.00"), summary.currencyTotals().get(0).totalAmount());

        assertEquals(1, reports.contributions(fixture.organisationId(), fixture.nyikaA(),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")).size());
        assertEquals(1, reports.deposits(fixture.organisationId(), fixture.nyikaA(),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")).size());
        assertEquals(1, reports.exceptions(fixture.organisationId(), fixture.nyikaA(),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")).size());
    }

    @Test
    void currencyTotalsRemainSeparateAndDateRangeIncludesBothEndpoints() throws Exception {
        Fixture fixture = fixture();
        UUID memberId = memberForCell(fixture.organisationId(), fixture.cellA(), "RANGE");
        UUID categoryId = jdbc.queryForObject(
            "SELECT id FROM contribution_categories WHERE organisation_id = ?",
            UUID.class, fixture.organisationId());
        UUID streamId = jdbc.queryForObject(
            "SELECT id FROM financial_streams WHERE organisation_id = ?",
            UUID.class, fixture.organisationId());

        contribution(fixture.organisationId(), memberId, categoryId, streamId, fixture.nyikaA(),
            fixture.adminId(), "RCP-USD-1", 3, new BigDecimal("25.00"), "USD", LocalDate.parse("2026-01-01"));
        contribution(fixture.organisationId(), memberId, categoryId, streamId, fixture.nyikaA(),
            fixture.adminId(), "RCP-ZAR-2", 4, new BigDecimal("50.00"), "ZAR", LocalDate.parse("2026-12-31"));
        contribution(fixture.organisationId(), memberId, categoryId, streamId, fixture.nyikaA(),
            fixture.adminId(), "RCP-OUT-1", 5, new BigDecimal("700.00"), "ZAR", LocalDate.parse("2025-12-31"));
        contribution(fixture.organisationId(), memberId, categoryId, streamId, fixture.nyikaA(),
            fixture.adminId(), "RCP-OUT-2", 6, new BigDecimal("800.00"), "USD", LocalDate.parse("2027-01-01"));

        authenticate(fixture);
        LocalDate from = LocalDate.parse("2026-01-01");
        LocalDate to = LocalDate.parse("2026-12-31");
        FinancialReportController.ContributionSummary summary =
            reports.summary(fixture.organisationId(), fixture.nyikaA(), from, to);

        assertEquals(3, summary.contributionCount());
        assertEquals(2, summary.currencyTotals().size());
        assertEquals("USD", summary.currencyTotals().get(0).currency());
        assertEquals(new BigDecimal("25.00"), summary.currencyTotals().get(0).totalAmount());
        assertEquals("ZAR", summary.currencyTotals().get(1).currency());
        assertEquals(new BigDecimal("175.00"), summary.currencyTotals().get(1).totalAmount());
        assertEquals(3, reports.contributions(fixture.organisationId(), fixture.nyikaA(), from, to).size());

        assertThrows(IllegalArgumentException.class,
            () -> reports.summary(fixture.organisationId(), fixture.nyikaA(), to, from));

        var response = reports.export(fixture.organisationId(), fixture.nyikaA(), from, to, "CONTRIBUTION_SUMMARY");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(response.getBody()))) {
            String paragraphs = document.getParagraphs().stream()
                .map(paragraph -> paragraph.getText()).reduce("", (left, right) -> left + " " + right);
            assertTrue(paragraphs.contains("Total contributions (USD): 25.00"));
            assertTrue(paragraphs.contains("Total contributions (ZAR): 175.00"));
            assertFalse(paragraphs.contains("Total contributions: 200.00"));
        }
    }

    @Test
    void userCannotRequestAnotherNyikasReports() {
        Fixture fixture = fixture();
        authenticate(fixture);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.summary(fixture.organisationId(), fixture.nyikaB(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
    }


    @Test
    void financialReportsCannotCrossOrganisationBoundaries() {
        Fixture assigned = fixture();
        Fixture otherOrganisation = fixture();
        authenticate(assigned);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.units(otherOrganisation.organisationId()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.summary(otherOrganisation.organisationId(), otherOrganisation.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.contributions(otherOrganisation.organisationId(), otherOrganisation.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.deposits(otherOrganisation.organisationId(), otherOrganisation.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.exceptions(otherOrganisation.organisationId(), otherOrganisation.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.export(otherOrganisation.organisationId(), otherOrganisation.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "CONTRIBUTION_SUMMARY"));

        assertThrows(ResourceNotFoundException.class,
            () -> reports.summary(otherOrganisation.organisationId(), assigned.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
    }

    @Test
    void userWithoutFinanceViewCannotReadOrExportReports() {
        Fixture fixture = fixture();
        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
            VALUES (?, 'membership.only', 'Membership Only', 'not-used-in-direct-controller-test', TRUE)
            """, userId);
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000004', 'UNIT', ?, ?)
            """, UUID.randomUUID(), userId, fixture.organisationId(), fixture.nyikaA());

        Integer financeViewAssignments = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            WHERE a.user_id = ? AND a.revoked_at IS NULL
              AND rp.permission_code = 'FINANCE_VIEW'
            """, Integer.class, userId);
        assertEquals(0, financeViewAssignments);

        authenticateAs("membership.only");

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.summary(fixture.organisationId(), fixture.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.export(fixture.organisationId(), fixture.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "CONTRIBUTION_REGISTER"));
    }

    @Test
    void userWithoutFinanceViewIsDeniedAcrossEveryReportEndpoint() {
        Fixture fixture = fixture();
        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
            VALUES (?, 'tabhera.membership', 'Tabhera Membership Admin', 'not-used', TRUE)
            """, userId);
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000004', 'UNIT', ?, ?)
            """, UUID.randomUUID(), userId, fixture.organisationId(), fixture.nyikaA());

        Integer financeViewAssignments = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            WHERE a.user_id = ? AND a.revoked_at IS NULL
              AND rp.permission_code = 'FINANCE_VIEW'
            """, Integer.class, userId);
        assertEquals(0, financeViewAssignments);

        authenticateAs("tabhera.membership");
        LocalDate from = LocalDate.parse("2026-01-01");
        LocalDate to = LocalDate.parse("2026-12-31");

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.units(fixture.organisationId()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.summary(fixture.organisationId(), fixture.nyikaA(), from, to));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.contributions(fixture.organisationId(), fixture.nyikaA(), from, to));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.deposits(fixture.organisationId(), fixture.nyikaA(), from, to));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.exceptions(fixture.organisationId(), fixture.nyikaA(), from, to));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.export(fixture.organisationId(), fixture.nyikaA(), from, to, "CONTRIBUTION_SUMMARY"));
    }

    @Test
    void assignedNyikaCanReadDescendantButNotSiblingNyikaReports() {
        Fixture fixture = fixture();
        authenticate(fixture);
        LocalDate from = LocalDate.parse("2026-01-01");
        LocalDate to = LocalDate.parse("2026-12-31");

        assertDoesNotThrow(() -> reports.summary(fixture.organisationId(), fixture.cellA(), from, to));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.summary(fixture.organisationId(), fixture.cellB(), from, to));
    }

    @Test
    void everyWordExportTypeWritesAnAuditEvent() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture);
        LocalDate from = LocalDate.parse("2026-01-01");
        LocalDate to = LocalDate.parse("2026-12-31");

        for (String type : List.of("CONTRIBUTION_SUMMARY", "CONTRIBUTION_REGISTER",
                "DEPOSIT_REGISTER", "EXCEPTION_REGISTER")) {
            var response = reports.export(fixture.organisationId(), fixture.nyikaA(), from, to, type);
            assertEquals(200, response.getStatusCode().value(), type);
            assertNotNull(response.getBody(), type);
            assertTrue(response.getBody().length > 0, type);
        }

        Integer auditCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_audit_events
            WHERE actor_user_id = ? AND action_code = 'FINANCIAL_REPORT_GENERATED'
            """, Integer.class, fixture.adminId());
        assertEquals(4, auditCount);
    }

    @Test
    void revokedFinancialAssignmentCannotAccessReports() {
        Fixture fixture = fixture();
        jdbc.update("""
            UPDATE admin_role_assignments SET revoked_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND scope_unit_id = ?
            """, fixture.adminId(), fixture.nyikaA());
        authenticate(fixture);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.summary(fixture.organisationId(), fixture.nyikaA(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
    }

    @Test
    void wordExportProducesReadableDocxAndWritesAuditEvent() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture);

        var response = reports.export(fixture.organisationId(), fixture.nyikaA(),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "CONTRIBUTION_SUMMARY");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(response.getBody()))) {
            String text = document.getParagraphs().stream()
                .map(paragraph -> paragraph.getText()).reduce("", (left, right) -> left + " " + right);
            assertTrue(text.contains("ZION CHRISTIAN CHURCH"));
            assertTrue(text.contains("CONTRIBUTION SUMMARY"));
            assertTrue(text.contains("CONFIDENTIAL"));
            assertEquals(1, document.getTables().size());
            assertTrue(document.getTables().get(0).getText().contains("125.00"));
        }

        Integer auditCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_audit_events
            WHERE actor_user_id = ? AND action_code = 'FINANCIAL_REPORT_GENERATED'
            """, Integer.class, fixture.adminId());
        assertEquals(1, auditCount);
    }

    @Test
    void everyWordRegisterExportContainsExpectedHeadersAndFixtureRecords() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture);
        LocalDate from = LocalDate.parse("2026-01-01");
        LocalDate to = LocalDate.parse("2026-12-31");

        assertExportTable(fixture, from, to, "CONTRIBUTION_REGISTER",
            List.of("Date", "Receipt", "Contributor", "Category", "Stream", "Payment", "Currency", "Amount"),
            List.of("RCP-A-1", "TestA Member", "Tithing", "Main", "CASH", "ZAR", "125.00"));

        assertExportTable(fixture, from, to, "DEPOSIT_REGISTER",
            List.of("Date", "Unit", "Bank", "Reference", "Expected", "Allocated", "Banked", "Outstanding", "Status"),
            List.of("Nyika A", "Test Bank", "BANK-A-1", "125.00", "0", "SUBMITTED"));

        assertExportTable(fixture, from, to, "EXCEPTION_REGISTER",
            List.of("Date", "Unit", "Type", "Status", "Summary"),
            List.of("Nyika A", "DEPOSIT_AMOUNT_MISMATCH", "Test exception"));
    }


    @Test
    void wordExportsEnforceNyikaScopeAndDateRangeForEveryReportType() throws Exception {
        Fixture fixture = fixture();
        UUID categoryId = jdbc.queryForObject(
            "SELECT id FROM contribution_categories WHERE organisation_id = ?",
            UUID.class, fixture.organisationId());
        UUID streamId = jdbc.queryForObject(
            "SELECT id FROM financial_streams WHERE organisation_id = ?",
            UUID.class, fixture.organisationId());
        UUID memberId = memberForCell(fixture.organisationId(), fixture.cellA(), "OLD");

        contribution(fixture.organisationId(), memberId, categoryId, streamId, fixture.nyikaA(),
            fixture.adminId(), "RCP-OLD-1", 99, new BigDecimal("700.00"), "ZAR",
            LocalDate.parse("2025-12-31"));

        jdbc.update("""
            INSERT INTO deposit_batches
                (id, organisation_id, scope_unit_id, deposit_date, bank_name, bank_reference,
                 expected_amount, status, created_by)
            VALUES (?, ?, ?, DATE '2025-12-31', 'Test Bank', 'BANK-OLD-1', 55.00, 'SUBMITTED', ?)
            """, UUID.randomUUID(), fixture.organisationId(), fixture.nyikaA(), fixture.adminId());

        jdbc.update("""
            INSERT INTO reconciliation_exceptions
                (id, organisation_id, scope_unit_id, source_type, source_id, exception_type,
                 summary, created_at)
            VALUES (?, ?, ?, 'DEPOSIT_BATCH', ?, 'DEPOSIT_AMOUNT_MISMATCH',
                    'Out of period exception', TIMESTAMPTZ '2025-12-31 12:00:00+00')
            """, UUID.randomUUID(), fixture.organisationId(), fixture.nyikaA(), UUID.randomUUID());

        authenticate(fixture);
        LocalDate from = LocalDate.parse("2026-01-01");
        LocalDate to = LocalDate.parse("2026-12-31");

        String summary = exportText(fixture, from, to, "CONTRIBUTION_SUMMARY");
        assertTrue(summary.contains("125.00"));
        assertFalse(summary.contains("1025.00"));
        assertFalse(summary.contains("900.00"));
        assertFalse(summary.contains("700.00"));

        String contributions = exportText(fixture, from, to, "CONTRIBUTION_REGISTER");
        assertTrue(contributions.contains("RCP-A-1"));
        assertTrue(contributions.contains("TestA Member"));
        assertFalse(contributions.contains("RCP-B-1"));
        assertFalse(contributions.contains("TestB Member"));
        assertFalse(contributions.contains("RCP-OLD-1"));

        String deposits = exportText(fixture, from, to, "DEPOSIT_REGISTER");
        assertTrue(deposits.contains("BANK-A-1"));
        assertFalse(deposits.contains("BANK-B-1"));
        assertFalse(deposits.contains("BANK-OLD-1"));

        String exceptions = exportText(fixture, from, to, "EXCEPTION_REGISTER");
        assertTrue(exceptions.contains("Test exception"));
        assertFalse(exceptions.contains("Other Nyika exception"));
        assertFalse(exceptions.contains("Out of period exception"));
    }

    @Test
    void apiResponsesEnforceUnitScopeAndInclusiveDateRangeAcrossRegisters() {
        Fixture fixture = fixture();
        UUID memberId = memberForCell(fixture.organisationId(), fixture.cellA(), "API_RANGE");
        UUID categoryId = jdbc.queryForObject(
            "SELECT id FROM contribution_categories WHERE organisation_id = ?",
            UUID.class, fixture.organisationId());
        UUID streamId = jdbc.queryForObject(
            "SELECT id FROM financial_streams WHERE organisation_id = ?",
            UUID.class, fixture.organisationId());

        contribution(fixture.organisationId(), memberId, categoryId, streamId, fixture.nyikaA(),
            fixture.adminId(), "RCP-API-BEFORE", 101, new BigDecimal("700.00"), "ZAR",
            LocalDate.parse("2025-12-31"));
        contribution(fixture.organisationId(), memberId, categoryId, streamId, fixture.nyikaA(),
            fixture.adminId(), "RCP-API-AFTER", 102, new BigDecimal("800.00"), "ZAR",
            LocalDate.parse("2027-01-01"));

        jdbc.update("""
            INSERT INTO deposit_batches
                (id, organisation_id, scope_unit_id, deposit_date, bank_name, bank_reference,
                 expected_amount, status, created_by)
            VALUES (?, ?, ?, DATE '2025-12-31', 'Test Bank', 'BANK-API-OLD', 700.00, 'SUBMITTED', ?)
            """, UUID.randomUUID(), fixture.organisationId(), fixture.nyikaA(), fixture.adminId());

        jdbc.update("""
            INSERT INTO reconciliation_exceptions
                (id, organisation_id, scope_unit_id, source_type, source_id, exception_type,
                 summary, created_at)
            VALUES (?, ?, ?, 'DEPOSIT_BATCH', ?, 'DEPOSIT_AMOUNT_MISMATCH',
                    'API out of period exception', TIMESTAMPTZ '2027-01-01 12:00:00+00')
            """, UUID.randomUUID(), fixture.organisationId(), fixture.nyikaA(), UUID.randomUUID());

        authenticate(fixture);
        LocalDate from = LocalDate.parse("2026-01-01");
        LocalDate to = LocalDate.parse("2026-12-31");

        FinancialReportController.ContributionSummary summary =
            reports.summary(fixture.organisationId(), fixture.nyikaA(), from, to);
        assertEquals(1, summary.contributionCount());
        assertEquals(new BigDecimal("125.00"), summary.currencyTotals().get(0).totalAmount());

        List<FinancialReportController.ContributionLine> contributions =
            reports.contributions(fixture.organisationId(), fixture.nyikaA(), from, to);
        assertEquals(1, contributions.size());
        assertEquals("RCP-A-1", contributions.get(0).receiptNumber());

        List<FinancialReportController.DepositLine> deposits =
            reports.deposits(fixture.organisationId(), fixture.nyikaA(), from, to);
        assertEquals(1, deposits.size());
        assertEquals("BANK-A-1", deposits.get(0).bankReference());

        List<FinancialReportController.ExceptionLine> exceptions =
            reports.exceptions(fixture.organisationId(), fixture.nyikaA(), from, to);
        assertEquals(1, exceptions.size());
        assertEquals("Test exception", exceptions.get(0).summary());

        assertFalse(contributions.stream().anyMatch(item ->
            item.receiptNumber().equals("RCP-B-1")
                || item.receiptNumber().equals("RCP-API-BEFORE")
                || item.receiptNumber().equals("RCP-API-AFTER")));
        assertFalse(deposits.stream().anyMatch(item -> item.bankReference().equals("BANK-API-OLD")));
        assertFalse(exceptions.stream().anyMatch(item -> item.summary().equals("API out of period exception")));
    }

    @Test
    void auditPayloadIncludesVersionedActorAndOrganisationScopeMetadata() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture);
        UUID targetId = UUID.randomUUID();

        audit.record("AUDIT_METADATA_TEST", "TEST_TARGET", targetId,
            Map.of("organisationId", fixture.organisationId(), "cellId", fixture.cellA()));

        String details = jdbc.queryForObject("""
            SELECT details::text FROM admin_audit_events WHERE target_id = ?
            """, String.class, targetId);
        assertNotNull(details);
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(details);
        var metadata = payload.path("_metadata");
        assertEquals(1, metadata.path("schemaVersion").asInt());
        assertEquals(fixture.adminId().toString(), metadata.path("actorUserId").asText());
        assertEquals(fixture.username(), metadata.path("actorUsername").asText());
        assertEquals(fixture.organisationId().toString(), metadata.path("organisationId").asText());
        assertEquals(fixture.cellA().toString(), metadata.path("scopeUnitId").asText());
    }

    private String exportText(Fixture fixture, LocalDate from, LocalDate to, String type) throws Exception {
        var response = reports.export(fixture.organisationId(), fixture.nyikaA(), from, to, type);
        assertEquals(200, response.getStatusCode().value(), type);
        assertNotNull(response.getBody(), type);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(response.getBody()))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append(' '));
            document.getTables().forEach(table -> text.append(table.getText()).append(' '));
            return text.toString();
        }
    }

    private void assertExportTable(Fixture fixture, LocalDate from, LocalDate to, String type,
            List<String> expectedHeaders, List<String> expectedValues) throws Exception {
        var response = reports.export(fixture.organisationId(), fixture.nyikaA(), from, to, type);
        assertEquals(200, response.getStatusCode().value(), type);
        assertNotNull(response.getBody(), type);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(response.getBody()))) {
            assertEquals(1, document.getTables().size(), type);
            var table = document.getTables().get(0);
            assertEquals(expectedHeaders.size(), table.getRow(0).getTableCells().size(), type);
            String tableText = table.getText();
            expectedHeaders.forEach(value -> assertTrue(tableText.contains(value), type + " missing header " + value));
            expectedValues.forEach(value -> assertTrue(tableText.contains(value), type + " missing value " + value));
            assertTrue(table.getNumberOfRows() >= 2, type + " should contain a header and at least one record");
        }
    }

    @Test
    void depositLifecycleAllocatesEachReceiptOnceAndReconcilesAgainstBankEvidence() {
        Fixture fixture = fixture();
        jdbc.update("""
            INSERT INTO admin_role_assignments
                (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'GLOBAL', NULL, NULL)
            """, UUID.randomUUID(), fixture.adminId());
        authenticate(fixture);

        UUID receiptId = jdbc.queryForObject(
            "SELECT id FROM contributions WHERE organisation_id = ? AND receipt_number = 'RCP-A-1'",
            UUID.class, fixture.organisationId());
        LocalDate depositDate = LocalDate.parse("2026-05-10");
        var batch = depositManagement.createBatch(fixture.organisationId(),
            new DepositManagementController.CreateDepositBatch(fixture.nyikaA(), depositDate,
                "Test Bank", "LIFECYCLE-REF-1", new BigDecimal("125.00"), null));

        var allocated = depositManagement.allocate(UUID.fromString(batch.id()),
            new DepositManagementController.AllocateReceipt(receiptId, new BigDecimal("125.00")));
        assertEquals(1, allocated.receiptCount());
        assertEquals(new BigDecimal("125.00"), allocated.allocatedAmount());
        UUID followupId = jdbc.queryForObject(
            "SELECT id FROM contribution_deposit_followups WHERE contribution_id = ?", UUID.class, receiptId);
        assertEquals("IN_DRAFT", jdbc.queryForObject(
            "SELECT status FROM contribution_deposit_followups WHERE id = ?", String.class, followupId));
        assertThrows(IllegalArgumentException.class, () -> contributionController.updateDepositFollowup(followupId,
            new ContributionController.UpdateDepositFollowup("RESOLVED", null)));
        assertThrows(ConflictException.class, () -> contributionController.updateDepositFollowup(followupId,
            new ContributionController.UpdateDepositFollowup("RESOLVED", "Attempted early resolution")));

        var secondBatch = depositManagement.createBatch(fixture.organisationId(),
            new DepositManagementController.CreateDepositBatch(fixture.nyikaA(), depositDate,
                "Test Bank", "LIFECYCLE-REF-2", new BigDecimal("125.00"), null));
        assertThrows(ConflictException.class,
            () -> depositManagement.allocate(UUID.fromString(secondBatch.id()),
                new DepositManagementController.AllocateReceipt(receiptId, new BigDecimal("125.00"))));
        depositManagement.removeAllocation(UUID.fromString(batch.id()), receiptId);
        assertEquals("PENDING", jdbc.queryForObject(
            "SELECT status FROM contribution_deposit_followups WHERE id = ?", String.class, followupId));
        depositManagement.allocate(UUID.fromString(batch.id()),
            new DepositManagementController.AllocateReceipt(receiptId, new BigDecimal("125.00")));

        var submitted = depositManagement.submit(UUID.fromString(batch.id()));
        assertEquals("SUBMITTED", submitted.status());
        depositReview.review(UUID.fromString(batch.id()), new DepositReviewController.ReviewDecision("ACCEPT", null));
        var reviewDetails = depositReview.details(UUID.fromString(batch.id()));
        assertEquals(1, reviewDetails.allocations().size());
        assertEquals("RCP-A-1", reviewDetails.allocations().get(0).receiptNumber());
        assertEquals("125.00", reviewDetails.allocations().get(0).allocatedAmount().toPlainString());
        assertTrue(reviewDetails.evidence().isEmpty());
        assertTrue(reviewDetails.documents().isEmpty());
        assertEquals("SUBMITTED", jdbc.queryForObject(
            "SELECT status FROM contribution_deposit_followups WHERE id = ?", String.class, followupId));

        var statement = depositManagement.recordStatementEntry(fixture.organisationId(),
            new DepositManagementController.RecordStatementEntry(fixture.nyikaA(), depositDate,
                "LIFECYCLE-REF-1", new BigDecimal("125.00"), "Verified deposit"));
        var reconciled = depositManagement.reconcile(UUID.fromString(batch.id()),
            new DepositManagementController.ReconcileDeposit(UUID.fromString(statement.id()), "Matched to statement"));
        assertEquals("RECONCILED", reconciled.status());
        assertEquals("RESOLVED", jdbc.queryForObject(
            "SELECT status FROM contribution_deposit_followups WHERE id = ?", String.class, followupId));
        assertTrue(depositManagement.statementEntries(fixture.organisationId(), fixture.nyikaA())
            .stream().filter(entry -> entry.id().equals(statement.id())).findFirst().orElseThrow().reconciled());

        assertThrows(ConflictException.class,
            () -> depositManagement.reconcile(UUID.fromString(batch.id()),
                new DepositManagementController.ReconcileDeposit(UUID.fromString(statement.id()), "Duplicate attempt")));
    }

    @Test
    void depositAllocationRejectsPartialAmountsAndReceiptsFromAnotherScope() {
        Fixture fixture = fixture();
        authenticate(fixture);
        UUID receiptA = jdbc.queryForObject(
            "SELECT id FROM contributions WHERE organisation_id = ? AND receipt_number = 'RCP-A-1'",
            UUID.class, fixture.organisationId());
        UUID receiptB = jdbc.queryForObject(
            "SELECT id FROM contributions WHERE organisation_id = ? AND receipt_number = 'RCP-B-1'",
            UUID.class, fixture.organisationId());
        var batch = depositManagement.createBatch(fixture.organisationId(),
            new DepositManagementController.CreateDepositBatch(fixture.nyikaA(),
                LocalDate.parse("2026-05-10"), "Test Bank", "SCOPE-REF-1",
                new BigDecimal("125.00"), null));

        assertThrows(IllegalArgumentException.class,
            () -> depositManagement.allocate(UUID.fromString(batch.id()),
                new DepositManagementController.AllocateReceipt(receiptA, new BigDecimal("100.00"))));
        assertThrows(IllegalArgumentException.class,
            () -> depositManagement.allocate(UUID.fromString(batch.id()),
                new DepositManagementController.AllocateReceipt(receiptB, new BigDecimal("900.00"))));
        assertEquals(0, depositManagement.batches(fixture.organisationId(), fixture.nyikaA())
            .stream().filter(item -> item.id().equals(batch.id())).findFirst().orElseThrow().receiptCount());
    }

    @Test
    void depositCanBeBankedInPartsWithExplainedOutstandingBalance() {
        Fixture fixture = fixture();
        jdbc.update("""
            INSERT INTO admin_role_assignments
                (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'GLOBAL', NULL, NULL)
            """, UUID.randomUUID(), fixture.adminId());
        authenticate(fixture);

        UUID receiptId = jdbc.queryForObject(
            "SELECT id FROM contributions WHERE organisation_id = ? AND receipt_number = 'RCP-A-1'",
            UUID.class, fixture.organisationId());
        LocalDate depositDate = LocalDate.parse("2026-05-10");
        var batch = depositManagement.createBatch(fixture.organisationId(),
            new DepositManagementController.CreateDepositBatch(fixture.nyikaA(), depositDate,
                "Test Bank", "PARTIAL-REF-1", new BigDecimal("125.00"), null));
        var allocated = depositManagement.allocate(UUID.fromString(batch.id()),
            new DepositManagementController.AllocateReceipt(receiptId, new BigDecimal("125.00")));
        assertEquals(new BigDecimal("125.00"), allocated.allocatedAmount());
        var submitted = depositManagement.submit(UUID.fromString(batch.id()));
        assertEquals("SUBMITTED", submitted.status());
        depositReview.review(UUID.fromString(batch.id()), new DepositReviewController.ReviewDecision("ACCEPT", null));

        var firstCredit = depositManagement.recordStatementEntry(fixture.organisationId(),
            new DepositManagementController.RecordStatementEntry(fixture.nyikaA(), depositDate,
                "PARTIAL-REF-1", new BigDecimal("75.00"), "First cash deposit"));
        assertThrows(IllegalArgumentException.class,
            () -> depositManagement.reconcile(UUID.fromString(batch.id()),
                new DepositManagementController.ReconcileDeposit(UUID.fromString(firstCredit.id()), null)));

        var debitEntry = depositManagement.recordStatementEntry(fixture.organisationId(),
            new DepositManagementController.RecordStatementEntry(fixture.nyikaA(), depositDate,
                "PARTIAL-REF-1", new BigDecimal("10.00"), "Bank debit"));
        jdbc.update("UPDATE bank_statement_entries SET entry_type = 'DEBIT' WHERE id = ?",
            UUID.fromString(debitEntry.id()));
        assertThrows(IllegalArgumentException.class,
            () -> depositManagement.reconcile(UUID.fromString(batch.id()),
                new DepositManagementController.ReconcileDeposit(UUID.fromString(debitEntry.id()), "Not a credit.")));

        var partiallyReconciled = depositManagement.reconcile(UUID.fromString(batch.id()),
            new DepositManagementController.ReconcileDeposit(UUID.fromString(firstCredit.id()),
                "Bank branch deposit limit; remaining cash will be deposited tomorrow."));
        assertEquals("PARTIALLY_RECONCILED", partiallyReconciled.status());
        assertEquals("PARTIALLY_BANKED", jdbc.queryForObject("""
            SELECT f.status FROM contribution_deposit_followups f
            JOIN deposit_allocations a ON a.contribution_id = f.contribution_id
            WHERE a.deposit_batch_id = ?
            """, String.class, UUID.fromString(batch.id())));
        assertEquals(new BigDecimal("75.00"), partiallyReconciled.bankedAmount());
        assertEquals(new BigDecimal("50.00"), partiallyReconciled.outstandingAmount());
        assertEquals("Bank branch deposit limit; remaining cash will be deposited tomorrow.",
            partiallyReconciled.outstandingNote());
        var partialReport = reports.deposits(fixture.organisationId(), fixture.nyikaA(), depositDate, depositDate)
            .stream().filter(item -> item.id().equals(batch.id())).findFirst().orElseThrow();
        assertEquals("PARTIALLY_RECONCILED", partialReport.status());
        assertEquals(new BigDecimal("75.00"), partialReport.bankedAmount());
        assertEquals(new BigDecimal("50.00"), partialReport.outstandingAmount());

        var excessiveCredit = depositManagement.recordStatementEntry(fixture.organisationId(),
            new DepositManagementController.RecordStatementEntry(fixture.nyikaA(), depositDate.plusDays(1),
                "PARTIAL-REF-1", new BigDecimal("60.00"), "Exceeds remaining balance"));
        assertThrows(IllegalArgumentException.class,
            () -> depositManagement.reconcile(UUID.fromString(batch.id()),
                new DepositManagementController.ReconcileDeposit(UUID.fromString(excessiveCredit.id()),
                    "This amount is too high.")));

        var finalCredit = depositManagement.recordStatementEntry(fixture.organisationId(),
            new DepositManagementController.RecordStatementEntry(fixture.nyikaA(), depositDate.plusDays(1),
                "PARTIAL-REF-1", new BigDecimal("50.00"), "Remaining cash deposit"));
        var fullyReconciled = depositManagement.reconcile(UUID.fromString(batch.id()),
            new DepositManagementController.ReconcileDeposit(UUID.fromString(finalCredit.id()),
                "Outstanding balance deposited in full."));
        assertEquals("RECONCILED", fullyReconciled.status());
        assertEquals("RESOLVED", jdbc.queryForObject("""
            SELECT f.status FROM contribution_deposit_followups f
            JOIN deposit_allocations a ON a.contribution_id = f.contribution_id
            WHERE a.deposit_batch_id = ?
            """, String.class, UUID.fromString(batch.id())));
        assertEquals(new BigDecimal("125.00"), fullyReconciled.bankedAmount());
        assertEquals(new BigDecimal("0.00"), fullyReconciled.outstandingAmount());
        assertTrue(depositManagement.statementEntries(fixture.organisationId(), fixture.nyikaA()).stream()
            .filter(entry -> entry.id().equals(firstCredit.id()) || entry.id().equals(finalCredit.id()))
            .allMatch(DepositManagementController.BankStatementEntryDto::reconciled));
    }

    @Test
    void expenditureRequiresIndependentApprovalAndTracksPaymentInFinancialReports() throws Exception {
        Fixture fixture = fixture();
        UUID capturerId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        String capturer = "expense.capturer." + capturerId.toString().replace("-", "").substring(0, 12);
        String approver = "expense.approver." + approverId.toString().replace("-", "").substring(0, 12);
        for (var user : List.of(Map.entry(capturerId, capturer), Map.entry(approverId, approver))) {
            jdbc.update("""
                INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
                VALUES (?, ?, 'Expense Administrator', 'not-used-in-direct-controller-test', TRUE)
                """, user.getKey(), user.getValue());
            jdbc.update("""
                INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
                VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'GLOBAL', NULL, NULL)
                """, UUID.randomUUID(), user.getKey());
        }

        authenticateAs(capturer);
        var category = expenditureManagement.createCategory(fixture.organisationId(),
            new ExpenditureManagementController.CreateExpenseCategory("UTILITIES", "Utilities", "Electricity and water"));
        var created = expenditureManagement.create(fixture.organisationId(),
            new ExpenditureManagementController.CreateExpenditure(fixture.nyikaA(), UUID.fromString(category.getBody().id()),
                LocalDate.parse("2026-05-10"), "Municipal account", "Monthly electricity account",
                "INV-UTIL-001", new BigDecimal("45.00"), "ZAR"));
        assertEquals("DRAFT", created.getBody().status());

        var submitted = expenditureManagement.submit(UUID.fromString(created.getBody().id()));
        assertEquals("SUBMITTED", submitted.status());
        assertThrows(IllegalArgumentException.class,
            () -> expenditureManagement.review(UUID.fromString(created.getBody().id()),
                new ExpenditureManagementController.ReviewExpenditure("APPROVE", "Self approval must be blocked.")));

        authenticateAs(approver);
        assertThrows(IllegalArgumentException.class,
            () -> expenditureManagement.review(UUID.fromString(created.getBody().id()),
                new ExpenditureManagementController.ReviewExpenditure("REJECT", " ")));
        var approved = expenditureManagement.review(UUID.fromString(created.getBody().id()),
            new ExpenditureManagementController.ReviewExpenditure("APPROVE", "Invoice checked against supporting document."));
        assertEquals("APPROVED", approved.status());
        var paid = expenditureManagement.pay(UUID.fromString(created.getBody().id()),
            new ExpenditureManagementController.PayExpenditure("EFT-2026-0045"));
        assertEquals("PAID", paid.status());
        assertEquals("EFT-2026-0045", paid.paymentReference());

        LocalDate date = LocalDate.parse("2026-05-10");
        var register = reports.expenses(fixture.organisationId(), fixture.nyikaA(), date, date);
        assertEquals(1, register.size());
        assertEquals("PAID", register.get(0).status());
        assertEquals(new BigDecimal("45.00"), register.get(0).amount());

        var position = reports.incomeExpenditure(fixture.organisationId(), fixture.nyikaA(), date, date);
        assertEquals(1, position.positions().size());
        assertEquals("ZAR", position.positions().get(0).currency());
        assertEquals(new BigDecimal("125.00"), position.positions().get(0).income());
        assertEquals(new BigDecimal("45.00"), position.positions().get(0).expenditure());
        assertEquals(new BigDecimal("80.00"), position.positions().get(0).netPosition());

        var expenseExport = reports.export(fixture.organisationId(), fixture.nyikaA(), date, date, "EXPENDITURE_REGISTER");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(expenseExport.getBody()))) {
            String tableText = document.getTables().stream().flatMap(table -> table.getRows().stream())
                .flatMap(row -> row.getTableCells().stream()).map(cell -> cell.getText())
                .reduce("", (left, right) -> left + " " + right);
            assertTrue(tableText.contains("Municipal account"));
            assertTrue(tableText.contains("45.00"));
            assertTrue(tableText.contains("PAID"));
        }
        var positionExport = reports.export(fixture.organisationId(), fixture.nyikaA(), date, date, "INCOME_EXPENDITURE");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(positionExport.getBody()))) {
            String tableText = document.getTables().stream().flatMap(table -> table.getRows().stream())
                .flatMap(row -> row.getTableCells().stream()).map(cell -> cell.getText())
                .reduce("", (left, right) -> left + " " + right);
            assertTrue(tableText.contains("125.00"));
            assertTrue(tableText.contains("45.00"));
            assertTrue(tableText.contains("80.00"));
        }
    }


    @Test
    void draftExpenditureCanBeAmendedWithVersionChecksOrWithdrawnWithReason() {
        Fixture fixture = fixture();
        UUID centralId = UUID.randomUUID();
        String username = "draft.editor." + centralId.toString().replace("-", "").substring(0, 12);
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
            VALUES (?, ?, 'Draft Editor', 'not-used-in-direct-controller-test', TRUE)
            """, centralId, username);
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'GLOBAL', NULL, NULL)
            """, UUID.randomUUID(), centralId);
        authenticateAs(username);

        var category = expenditureManagement.createCategory(fixture.organisationId(),
            new ExpenditureManagementController.CreateExpenseCategory("OFFICE", "Office", "Office costs"));
        UUID categoryId = UUID.fromString(category.getBody().id());
        var created = expenditureManagement.create(fixture.organisationId(),
            new ExpenditureManagementController.CreateExpenditure(fixture.nyikaA(), categoryId,
                LocalDate.parse("2026-06-01"), "Stationery supplier", "Initial draft",
                "INV-DRAFT-01", new BigDecimal("100.00"), "ZAR"));
        UUID expenseId = UUID.fromString(created.getBody().id());
        assertEquals(1, created.getBody().version());

        var amended = expenditureManagement.amendDraft(expenseId,
            new ExpenditureManagementController.AmendDraftExpenditure(categoryId,
                LocalDate.parse("2026-06-02"), "Stationery supplier", "Corrected stationery details",
                "INV-DRAFT-01", new BigDecimal("125.00"), "ZAR", 1));
        assertEquals(2, amended.version());
        assertEquals(new BigDecimal("125.00"), amended.amount());
        assertEquals("2026-06-02", amended.expenseDate());

        assertThrows(ConflictException.class, () -> expenditureManagement.amendDraft(expenseId,
            new ExpenditureManagementController.AmendDraftExpenditure(categoryId,
                LocalDate.parse("2026-06-02"), "Stationery supplier", "Stale update",
                "INV-DRAFT-01", new BigDecimal("130.00"), "ZAR", 1)));

        assertThrows(IllegalArgumentException.class, () -> expenditureManagement.voidDraft(expenseId,
            new ExpenditureManagementController.VoidDraftExpenditure(" ")));
        var voided = expenditureManagement.voidDraft(expenseId,
            new ExpenditureManagementController.VoidDraftExpenditure("Duplicate draft entered in error."));
        assertEquals("VOIDED", voided.status());
        assertEquals("Duplicate draft entered in error.", voided.voidReason());
        assertEquals(3, voided.version());
        assertThrows(ConflictException.class, () -> expenditureManagement.submit(expenseId));

        Integer auditCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_audit_events
            WHERE target_id = ? AND action_code IN ('EXPENDITURE_DRAFT_AMENDED', 'EXPENDITURE_DRAFT_VOIDED')
            """, Integer.class, expenseId);
        assertEquals(2, auditCount);
    }

    @Test
    void expenditureEvidenceIsPrivateImmutableDeduplicatedAndAudited() throws Exception {
        Fixture fixture = fixture();
        UUID centralId = UUID.randomUUID();
        String username = "evidence.admin." + centralId.toString().replace("-", "").substring(0, 12);
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
            VALUES (?, ?, 'Evidence Administrator', 'not-used-in-direct-controller-test', TRUE)
            """, centralId, username);
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'GLOBAL', NULL, NULL)
            """, UUID.randomUUID(), centralId);
        authenticateAs(username);

        var category = expenditureManagement.createCategory(fixture.organisationId(),
            new ExpenditureManagementController.CreateExpenseCategory("REPAIRS", "Repairs", "Building repairs"));
        var expense = expenditureManagement.create(fixture.organisationId(),
            new ExpenditureManagementController.CreateExpenditure(fixture.nyikaA(),
                UUID.fromString(category.getBody().id()), LocalDate.parse("2026-07-01"),
                "Roofing supplier", "Repair work", "INV-REP-01", new BigDecimal("500.00"), "ZAR"));
        UUID expenseId = UUID.fromString(expense.getBody().id());
        byte[] pdf = "%PDF-1.7 evidence sample".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile("files", "invoice.pdf", "application/pdf", pdf);

        var uploaded = expenditureEvidence.upload(expenseId, List.of(file));
        assertEquals(1, uploaded.size());
        assertEquals("invoice.pdf", uploaded.get(0).filename());
        assertEquals("application/pdf", uploaded.get(0).contentType());
        assertEquals(pdf.length, uploaded.get(0).fileSize());
        assertEquals(username, uploaded.get(0).uploadedBy());
        assertThrows(ConflictException.class, () -> expenditureEvidence.upload(expenseId, List.of(file)));

        var content = expenditureEvidence.content(expenseId, UUID.fromString(uploaded.get(0).id()));
        assertArrayEquals(pdf, content.getBody());
        assertEquals("no-store", content.getHeaders().getCacheControl());
        Integer auditCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_audit_events
            WHERE target_id = ? AND action_code = 'EXPENDITURE_EVIDENCE_UPLOADED'
            """, Integer.class, UUID.fromString(uploaded.get(0).id()));
        assertEquals(1, auditCount);
    }

    @Test
    void budgetsTrackDescendantActualsAndKeepVersionedRevisionHistory() throws Exception {
        Fixture fixture = fixture();
        UUID centralId = UUID.randomUUID();
        String centralUsername = "budget.admin." + centralId.toString().replace("-", "").substring(0, 12);
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
            VALUES (?, ?, 'Budget Administrator', 'not-used-in-direct-controller-test', TRUE)
            """, centralId, centralUsername);
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'GLOBAL', NULL, NULL)
            """, UUID.randomUUID(), centralId);

        authenticateAs(centralUsername);
        var category = expenditureManagement.createCategory(fixture.organisationId(),
            new ExpenditureManagementController.CreateExpenseCategory("TRAVEL", "Travel", "Transport and travel costs"));
        UUID categoryId = UUID.fromString(category.getBody().id());
        var created = budgetManagement.create(fixture.organisationId(),
            new BudgetManagementController.CreateBudget(fixture.nyikaA(), categoryId,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
                new BigDecimal("1000.00"), "ZAR", "Annual travel allocation"));
        assertEquals(new BigDecimal("1000.00"), created.getBody().budgetAmount());
        assertEquals(new BigDecimal("0.00"), created.getBody().actualAmount());

        jdbc.update("""
            INSERT INTO expenditures
                (id, organisation_id, scope_unit_id, category_id, expense_date, payee, description,
                 amount, currency, status, captured_by)
            VALUES (?, ?, ?, ?, DATE '2026-05-10', 'Transport provider', 'Approved travel costs',
                    250.00, 'ZAR', 'APPROVED', ?)
            """, UUID.randomUUID(), fixture.organisationId(), fixture.cellA(), categoryId, fixture.adminId());

        authenticate(fixture);
        var performance = budgetManagement.budgets(fixture.organisationId(), fixture.nyikaA());
        assertEquals(1, performance.size());
        assertEquals(new BigDecimal("250.00"), performance.get(0).actualAmount());
        assertEquals(new BigDecimal("750.00"), performance.get(0).remainingAmount());
        assertEquals(new BigDecimal("25.00"), performance.get(0).utilisationPercentage());

        var budgetReport = reports.budgetPerformance(fixture.organisationId(), fixture.nyikaA(),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertEquals(1, budgetReport.size());
        assertEquals("Travel", budgetReport.get(0).categoryName());
        assertEquals(new BigDecimal("1000.00"), budgetReport.get(0).budgetAmount());
        assertEquals(new BigDecimal("250.00"), budgetReport.get(0).actualAmount());
        assertEquals(new BigDecimal("750.00"), budgetReport.get(0).remainingAmount());
        assertEquals(new BigDecimal("25.00"), budgetReport.get(0).utilisationPercentage());

        var budgetExport = reports.export(fixture.organisationId(), fixture.nyikaA(),
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "BUDGET_PERFORMANCE");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(budgetExport.getBody()))) {
            String tableText = document.getTables().stream().flatMap(table -> table.getRows().stream())
                .flatMap(row -> row.getTableCells().stream()).map(cell -> cell.getText())
                .reduce("", (left, right) -> left + " " + right);
            assertTrue(tableText.contains("1000.00"));
            assertTrue(tableText.contains("250.00"));
            assertTrue(tableText.contains("750.00"));
            assertTrue(tableText.contains("25.00%"));
        }

        authenticateAs(centralUsername);
        assertThrows(ConflictException.class, () -> budgetManagement.create(fixture.organisationId(),
            new BudgetManagementController.CreateBudget(fixture.cellA(), categoryId,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-09-30"),
                new BigDecimal("200.00"), "ZAR", "Overlapping child allocation")));

        var revised = budgetManagement.revise(UUID.fromString(created.getBody().id()),
            new BudgetManagementController.ReviseBudget(new BigDecimal("1200.00"), 1,
                "Approved additional transport allocation."));
        assertEquals(2, revised.version());
        assertEquals(new BigDecimal("1200.00"), revised.budgetAmount());
        assertThrows(ConflictException.class, () -> budgetManagement.revise(UUID.fromString(created.getBody().id()),
            new BudgetManagementController.ReviseBudget(new BigDecimal("1300.00"), 1, "Stale revision attempt.")));

        authenticate(fixture);
        var history = budgetManagement.revisions(UUID.fromString(created.getBody().id()));
        assertEquals(1, history.size());
        assertEquals(new BigDecimal("1000.00"), history.get(0).previousAmount());
        assertEquals(new BigDecimal("1200.00"), history.get(0).revisedAmount());
        assertEquals("Approved additional transport allocation.", history.get(0).reason());
    }

    @Test
    void depositEvidenceIsPrivateImmutableAndDuplicateProtected() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture);
        UUID batchId = jdbc.queryForObject(
            "SELECT id FROM deposit_batches WHERE organisation_id = ? AND bank_reference = 'BANK-A-1'",
            UUID.class, fixture.organisationId());
        byte[] original = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};
        var upload = new org.springframework.mock.web.MockMultipartFile(
            "files", "deposit-slip.jpg", "image/jpeg", original);

        var stored = depositEvidence.upload(batchId, List.of(upload));
        assertEquals(1, stored.size());
        assertEquals("image/jpeg", stored.get(0).contentType());
        assertEquals(original.length, stored.get(0).fileSize());
        assertEquals(64, stored.get(0).sha256().length());

        UUID evidenceId = UUID.fromString(stored.get(0).id());
        var downloaded = depositEvidence.content(batchId, evidenceId);
        assertArrayEquals(original, downloaded.getBody());
        assertEquals("image/jpeg", downloaded.getHeaders().getContentType().toString());
        assertThrows(ConflictException.class, () -> depositEvidence.upload(batchId, List.of(upload)));

        UUID otherBatch = jdbc.queryForObject(
            "SELECT id FROM deposit_batches WHERE organisation_id = ? AND bank_reference = 'BANK-B-1'",
            UUID.class, fixture.organisationId());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> depositEvidence.list(otherBatch));

        Integer retained = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deposit_evidence WHERE id = ?", Integer.class, evidenceId);
        assertEquals(1, retained);
    }

    @Test
    void scannedDepositDocumentCreatesMultiPagePdfWithoutChangingOriginalEvidence() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture);
        UUID batchId = jdbc.queryForObject(
            "SELECT id FROM deposit_batches WHERE organisation_id = ? AND bank_reference = 'BANK-A-1'",
            UUID.class, fixture.organisationId());

        BufferedImage pageImage = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = pageImage.createGraphics();
        graphics.fillRect(0, 0, 600, 800);
        graphics.drawString("Deposit slip page one", 80, 120);
        graphics.dispose();
        ByteArrayOutputStream imageOutput = new ByteArrayOutputStream();
        ImageIO.write(pageImage, "png", imageOutput);
        byte[] originalBytes = imageOutput.toByteArray();
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deposit_evidence
                (id, deposit_batch_id, original_filename, content_type, file_size, sha256, file_data, uploaded_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, evidenceId, batchId, "deposit-page.png", "image/png", originalBytes.length,
            "b".repeat(64), originalBytes, fixture.adminId());

        UUID secondEvidenceId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deposit_evidence
                (id, deposit_batch_id, original_filename, content_type, file_size, sha256, file_data, uploaded_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, secondEvidenceId, batchId, "deposit-page-two.png", "image/png", originalBytes.length,
            "c".repeat(64), originalBytes, fixture.adminId());
        var firstPage = new org.springframework.mock.web.MockMultipartFile(
            "pages", "deposit-page-one-scan.jpg", "image/jpeg", originalBytes);
        var secondPage = new org.springframework.mock.web.MockMultipartFile(
            "pages", "deposit-page-two-scan.jpg", "image/jpeg", originalBytes);
        var created = depositDocuments.create(batchId, List.of(firstPage, secondPage),
            List.of(evidenceId, secondEvidenceId));
        assertEquals(1, created.size());
        assertEquals(2, created.get(0).pageCount());
        assertTrue(created.get(0).fileSize() > 0);

        UUID documentId = UUID.fromString(created.get(0).id());
        byte[] pdf = depositDocuments.content(batchId, documentId).getBody();
        assertNotNull(pdf);
        assertTrue(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-"));
        try (var parsedPdf = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertEquals(2, parsedPdf.getNumberOfPages());
        }
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM deposit_document_sources WHERE document_id = ?",
            Integer.class, documentId));
        assertArrayEquals(originalBytes, jdbc.queryForObject(
            "SELECT file_data FROM deposit_evidence WHERE id = ?", byte[].class, evidenceId));
    }

    private Fixture fixture() {
        UUID organisationId = UUID.randomUUID();
        jdbc.update("INSERT INTO organisations (id, name) VALUES (?, ?)", organisationId, "Test Church");
        UUID country = unit(organisationId, null, "COUNTRY", "Country");
        UUID district = unit(organisationId, country, "DISTRICT", "District");
        UUID nyikaA = unit(organisationId, district, "NYIKA", "Nyika A");
        UUID tabheraA = unit(organisationId, nyikaA, "TABHERA", "Tabhera A");
        UUID cellA = unit(organisationId, tabheraA, "CELL", "Cell A");
        UUID nyikaB = unit(organisationId, district, "NYIKA", "Nyika B");
        UUID tabheraB = unit(organisationId, nyikaB, "TABHERA", "Tabhera B");
        UUID cellB = unit(organisationId, tabheraB, "CELL", "Cell B");

        UUID adminId = UUID.randomUUID();
        String username = "finance.reporter." + adminId.toString().replace("-", "").substring(0, 12);
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
            VALUES (?, ?, 'Financial Reporter', 'not-used-in-direct-controller-test', TRUE)
            """, adminId, username);
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, '10000000-0000-0000-0000-000000000003', 'UNIT', ?, ?)
            """, UUID.randomUUID(), adminId, organisationId, nyikaA);

        UUID streamId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO financial_streams (id, organisation_id, code, name, created_by)
            VALUES (?, ?, 'MAIN', 'Main', ?)
            """, streamId, organisationId, adminId);
        jdbc.update("""
            INSERT INTO contribution_categories
                (id, organisation_id, stream_id, code, name, contribution_type, created_by)
            VALUES (?, ?, ?, 'TITHING', 'Tithing', 'MANDATORY', ?)
            """, categoryId, organisationId, streamId, adminId);

        UUID memberA = member(organisationId, cellA, "A");
        UUID memberB = member(organisationId, cellB, "B");
        contribution(organisationId, memberA, categoryId, streamId, nyikaA, adminId,
            "RCP-A-1", 1, new BigDecimal("125.00"));
        contribution(organisationId, memberB, categoryId, streamId, nyikaB, adminId,
            "RCP-B-1", 2, new BigDecimal("900.00"));

        UUID depositId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deposit_batches
                (id, organisation_id, scope_unit_id, deposit_date, bank_name, bank_reference,
                 expected_amount, status, created_by)
            VALUES (?, ?, ?, DATE '2026-05-10', 'Test Bank', ?, 125.00, 'SUBMITTED', ?)
            """, depositId, organisationId, nyikaA, "BANK-A-1", adminId);

        jdbc.update("""
            INSERT INTO reconciliation_exceptions
                (id, organisation_id, scope_unit_id, source_type, source_id, exception_type, summary)
            VALUES (?, ?, ?, 'DEPOSIT_BATCH', ?, 'DEPOSIT_AMOUNT_MISMATCH', 'Test exception')
            """, UUID.randomUUID(), organisationId, nyikaA, depositId);

        UUID depositIdB = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deposit_batches
                (id, organisation_id, scope_unit_id, deposit_date, bank_name, bank_reference,
                 expected_amount, status, created_by)
            VALUES (?, ?, ?, DATE '2026-05-10', 'Test Bank', 'BANK-B-1', 900.00, 'SUBMITTED', ?)
            """, depositIdB, organisationId, nyikaB, adminId);

        jdbc.update("""
            INSERT INTO reconciliation_exceptions
                (id, organisation_id, scope_unit_id, source_type, source_id, exception_type, summary)
            VALUES (?, ?, ?, 'DEPOSIT_BATCH', ?, 'DEPOSIT_AMOUNT_MISMATCH', 'Other Nyika exception')
            """, UUID.randomUUID(), organisationId, nyikaB, depositIdB);

        return new Fixture(organisationId, adminId, username, nyikaA, cellA, nyikaB, cellB);
    }

    private UUID unit(UUID organisationId, UUID parentId, String level, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO organisational_units (id, organisation_id, parent_id, level, code, name)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, organisationId, parentId, level, name.toUpperCase().replace(' ', '_'), name);
        return id;
    }

    private UUID member(UUID organisationId, UUID cellId, String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO members
                (id, branch_id, zcc_app_number, first_name, last_name, active,
                 organisation_id, cell_id, cell_level)
            VALUES (?, NULL, ?, ?, 'Member', TRUE, ?, ?, 'CELL')
            """, id, "TEST-" + suffix + "-" + id, "Test" + suffix, organisationId, cellId);
        return id;
    }

    private UUID memberForCell(UUID organisationId, UUID cellId, String suffix) {
        return member(organisationId, cellId, suffix);
    }

    private void contribution(UUID organisationId, UUID memberId, UUID categoryId, UUID streamId,
            UUID scopeUnitId, UUID adminId, String receipt, long sequence, BigDecimal amount) {
        contribution(organisationId, memberId, categoryId, streamId, scopeUnitId, adminId,
            receipt, sequence, amount, "ZAR", LocalDate.parse("2026-05-10"));
    }

    private void contribution(UUID organisationId, UUID memberId, UUID categoryId, UUID streamId,
            UUID scopeUnitId, UUID adminId, String receipt, long sequence, BigDecimal amount,
            String currency, LocalDate contributionDate) {
        jdbc.update("""
            INSERT INTO contributions
                (id, organisation_id, member_id, category_id, stream_id, scope_unit_id,
                 receipt_number, receipt_sequence, amount, currency, contribution_date,
                 payment_method, idempotency_key, captured_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CASH', ?, ?)
            """, UUID.randomUUID(), organisationId, memberId, categoryId, streamId, scopeUnitId,
            receipt, sequence, amount, currency, contributionDate, "IDEMP-" + receipt, adminId);
        UUID contributionId = jdbc.queryForObject(
            "SELECT id FROM contributions WHERE organisation_id = ? AND receipt_number = ?",
            UUID.class, organisationId, receipt);
        jdbc.update("INSERT INTO contribution_deposit_followups (id, contribution_id) VALUES (?, ?)",
            UUID.randomUUID(), contributionId);
    }

    private void authenticate(Fixture fixture) {
        authenticateAs(fixture.username());
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "not-used", List.of()));
    }

    private record Fixture(UUID organisationId, UUID adminId, String username,
                           UUID nyikaA, UUID cellA, UUID nyikaB, UUID cellB) {}
}
