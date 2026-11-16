package za.co.zccbooks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
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

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unitListAndAllRegistersStayInsideAssignedNyika() {
        Fixture fixture = fixture();
        authenticate();

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
    void userCannotRequestAnotherNyikasReports() {
        Fixture fixture = fixture();
        authenticate();

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> reports.summary(fixture.organisationId(), fixture.nyikaB(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
    }

    @Test
    void wordExportProducesReadableDocxAndWritesAuditEvent() throws Exception {
        Fixture fixture = fixture();
        authenticate();

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
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash, enabled)
            VALUES (?, 'nyika.reporter', 'Nyika Reporter', 'not-used-in-direct-controller-test', TRUE)
            """, adminId);
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

        return new Fixture(organisationId, adminId, nyikaA, cellA, nyikaB, cellB);
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

    private void contribution(UUID organisationId, UUID memberId, UUID categoryId, UUID streamId,
            UUID scopeUnitId, UUID adminId, String receipt, long sequence, BigDecimal amount) {
        jdbc.update("""
            INSERT INTO contributions
                (id, organisation_id, member_id, category_id, stream_id, scope_unit_id,
                 receipt_number, receipt_sequence, amount, currency, contribution_date,
                 payment_method, idempotency_key, captured_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ZAR', DATE '2026-05-10',
                    'CASH', ?, ?)
            """, UUID.randomUUID(), organisationId, memberId, categoryId, streamId, scopeUnitId,
            receipt, sequence, amount, "IDEMP-" + receipt, adminId);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("nyika.reporter", "not-used", List.of()));
    }

    private record Fixture(UUID organisationId, UUID adminId, UUID nyikaA, UUID cellA,
                           UUID nyikaB, UUID cellB) {}
}
