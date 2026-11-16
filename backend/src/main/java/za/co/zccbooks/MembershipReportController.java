package za.co.zccbooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
@RequestMapping("/api/organisations/{organisationId}/membership-reports")
public class MembershipReportController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public MembershipReportController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/units")
    public List<ReportUnit> units(@PathVariable UUID organisationId) {
        authorization.requireOrganisationOrUnitPermission("MEMBERS_VIEW", organisationId);
        String sql = """
            SELECT id, level, code, name FROM organisational_units
            WHERE organisation_id = ? AND active = TRUE ORDER BY level, name
            """;
        Object[] args = new Object[] { organisationId };
        if (!authorization.hasOrganisationWidePermission("MEMBERS_VIEW", organisationId)) {
            sql = """
                WITH RECURSIVE visible_units AS (
                  SELECT a.scope_unit_id AS id
                  FROM admin_role_assignments a
                  JOIN admin_users u ON u.id = a.user_id
                  JOIN admin_role_permissions rp ON rp.role_id = a.role_id
                  WHERE a.scope_type = 'UNIT' AND a.organisation_id = ?
                    AND a.revoked_at IS NULL AND LOWER(u.username) = LOWER(?)
                    AND rp.permission_code = 'MEMBERS_VIEW'
                  UNION
                  SELECT child.id FROM organisational_units child
                  JOIN visible_units parent ON child.parent_id = parent.id
                  WHERE child.organisation_id = ?
                )
                SELECT ou.id, ou.level, ou.code, ou.name
                FROM organisational_units ou JOIN visible_units v ON v.id = ou.id
                WHERE ou.organisation_id = ? AND ou.active = TRUE ORDER BY ou.level, ou.name
                """;
            args = new Object[] { organisationId, authorization.currentUsername(), organisationId, organisationId };
        }
        return jdbc.query(sql, (rs,n) -> new ReportUnit(rs.getObject("id", UUID.class).toString(),
            rs.getString("level"), rs.getString("code"), rs.getString("name")), args);
    }

    @GetMapping("/members")
    public List<MemberLine> members(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        context(organisationId, scopeUnitId, from, to);
        return jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT m.id, m.first_name, m.last_name, m.zcc_app_number, m.active,
                   m.created_at::date AS registered_on, COALESCE(c.name, 'Unclassified') AS classification,
                   u.code AS cell_code, u.name AS cell_name
            FROM members m JOIN visible_units vu ON vu.id = m.cell_id
            JOIN organisational_units u ON u.id = m.cell_id
            LEFT JOIN classifications c ON c.id = m.classification_id
            WHERE m.organisation_id = ? AND m.created_at::date BETWEEN ? AND ?
            ORDER BY u.name, m.last_name, m.first_name
            """, (rs,n) -> new MemberLine(rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("zcc_app_number"), rs.getBoolean("active"), rs.getDate("registered_on").toLocalDate(),
                rs.getString("classification"), rs.getString("cell_code"), rs.getString("cell_name")),
            scopeUnitId, organisationId, organisationId, organisationId, from, to);
    }

    @GetMapping("/appointments")
    public List<AppointmentLine> appointments(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        context(organisationId, scopeUnitId, from, to);
        return jdbc.query("""
            WITH RECURSIVE visible_units AS (
              SELECT id FROM organisational_units WHERE id = ? AND organisation_id = ?
              UNION ALL
              SELECT child.id FROM organisational_units child
              JOIN visible_units parent ON child.parent_id = parent.id
              WHERE child.organisation_id = ?
            )
            SELECT m.first_name, m.last_name, m.zcc_app_number, p.code AS position_code,
                   p.name AS position_name, a.valid_from, a.valid_to,
                   su.level AS appointment_level, su.name AS appointment_scope, a.note
            FROM member_appointments a
            JOIN visible_units vu ON vu.id = a.scope_unit_id
            JOIN members m ON m.id = a.member_id AND m.organisation_id = a.organisation_id
            JOIN church_positions p ON p.id = a.position_id
            JOIN organisational_units su ON su.id = a.scope_unit_id
            WHERE a.organisation_id = ? AND a.valid_from <= ?
              AND COALESCE(a.valid_to, DATE '9999-12-31') >= ?
            ORDER BY p.name, su.name, m.last_name, m.first_name
            """, (rs,n) -> new AppointmentLine(rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("zcc_app_number"), rs.getString("position_code"), rs.getString("position_name"),
                rs.getDate("valid_from").toLocalDate(),
                rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate(),
                rs.getString("appointment_level"), rs.getString("appointment_scope"), rs.getString("note")),
            scopeUnitId, organisationId, organisationId, organisationId, to, from);
    }

    @GetMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> export(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId,
            @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam String type) throws IOException {
        ReportContext context = context(organisationId, scopeUnitId, from, to);
        if (!List.of("MEMBER_REGISTER", "APPOINTMENT_REGISTER").contains(type))
            throw new IllegalArgumentException("Unsupported membership report type.");
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun title = heading.createRun(); title.setBold(true); title.setFontSize(18);
            title.setText("ZION CHRISTIAN CHURCH");
            XWPFParagraph subtitle = document.createParagraph();
            subtitle.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun subtitleRun = subtitle.createRun(); subtitleRun.setBold(true); subtitleRun.setFontSize(14);
            subtitleRun.setText(type.equals("MEMBER_REGISTER") ? "MEMBERSHIP REGISTER" : "CHURCH APPOINTMENTS REGISTER");
            document.createParagraph().createRun().setText("Geographical scope: " + context.unitLevel() + " — " + context.unitName());
            document.createParagraph().createRun().setText("Reporting period: " + from + " to " + to);
            document.createParagraph().createRun().setText("Generated: " + OffsetDateTime.now());
            document.createParagraph().createRun().setText("Generated by: " + authorization.currentUsername());
            document.createParagraph().createRun().setText("CONFIDENTIAL — FOR AUTHORISED CHURCH ADMINISTRATION");
            document.createParagraph();
            if (type.equals("MEMBER_REGISTER")) {
                List<MemberLine> rows = members(organisationId, scopeUnitId, from, to);
                XWPFTable table = document.createTable(1, 6);
                setRow(table.getRow(0), "Member", "ZCC App Number", "Classification", "Cell", "Registered", "Status");
                for (MemberLine item : rows)
                    setRow(table.createRow(), item.lastName() + ", " + item.firstName(), item.zccAppNumber(),
                        item.classification(), item.cellCode() + " — " + item.cellName(),
                        item.registeredOn().toString(), item.active() ? "ACTIVE" : "INACTIVE");
                document.createParagraph().createRun().setText("Members listed: " + rows.size());
            } else {
                List<AppointmentLine> rows = appointments(organisationId, scopeUnitId, from, to);
                XWPFTable table = document.createTable(1, 7);
                setRow(table.getRow(0), "Member", "ZCC App Number", "Position", "Scope", "From", "To", "Note");
                for (AppointmentLine item : rows)
                    setRow(table.createRow(), item.lastName() + ", " + item.firstName(), item.zccAppNumber(),
                        item.positionName() + " (" + item.positionCode() + ")",
                        item.appointmentLevel() + " — " + item.appointmentScope(), item.validFrom().toString(),
                        item.validTo() == null ? "Ongoing" : item.validTo().toString(), item.note());
                document.createParagraph().createRun().setText("Appointments listed: " + rows.size());
            }
            document.write(output);
            byte[] bytes = output.toByteArray();
            audit.record("MEMBERSHIP_REPORT_GENERATED", "MEMBERSHIP_REPORT", UUID.randomUUID(),
                Map.of("organisationId", organisationId.toString(), "scopeUnitId", scopeUnitId.toString(),
                    "type", type, "from", from.toString(), "to", to.toString()));
            String filename = "zcc-" + type.toLowerCase().replace('_', '-') + "-" + from + "-to-" + to + ".docx";
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
        }
    }

    @GetMapping(value = "/export/pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable UUID organisationId, @RequestParam UUID scopeUnitId,
            @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam String type) throws IOException {
        ReportContext context = context(organisationId, scopeUnitId, from, to);
        if (!List.of("MEMBER_REGISTER", "APPOINTMENT_REGISTER").contains(type))
            throw new IllegalArgumentException("Unsupported membership report type.");
        List<String> headers;
        List<String[]> rows;
        if (type.equals("MEMBER_REGISTER")) {
            headers = List.of("Member", "ZCC App Number", "Classification", "Cell", "Registered", "Status");
            rows = members(organisationId, scopeUnitId, from, to).stream().map(item -> new String[] {
                item.lastName() + ", " + item.firstName(), item.zccAppNumber(), item.classification(),
                item.cellCode() + " - " + item.cellName(), item.registeredOn().toString(),
                item.active() ? "ACTIVE" : "INACTIVE"
            }).toList();
        } else {
            headers = List.of("Member", "ZCC App Number", "Position", "Scope", "From", "To", "Note");
            rows = appointments(organisationId, scopeUnitId, from, to).stream().map(item -> new String[] {
                item.lastName() + ", " + item.firstName(), item.zccAppNumber(),
                item.positionName() + " (" + item.positionCode() + ")",
                item.appointmentLevel() + " - " + item.appointmentScope(), item.validFrom().toString(),
                item.validTo() == null ? "Ongoing" : item.validTo().toString(), item.note()
            }).toList();
        }
        byte[] bytes = ReportPdfWriter.create(
            type.equals("MEMBER_REGISTER") ? "MEMBERSHIP REGISTER" : "CHURCH APPOINTMENTS REGISTER",
            context.unitLevel() + " - " + context.unitName(), from.toString(), to.toString(),
            authorization.currentUsername(), headers, rows);
        audit.record("MEMBERSHIP_REPORT_GENERATED", "MEMBERSHIP_REPORT", UUID.randomUUID(),
            Map.of("organisationId", organisationId.toString(), "scopeUnitId", scopeUnitId.toString(),
                "type", type, "format", "PDF", "from", from.toString(), "to", to.toString()));
        String filename = "zcc-" + type.toLowerCase().replace('_', '-') + "-" + from + "-to-" + to + ".pdf";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF).body(bytes);
    }

    private ReportContext context(UUID organisationId, UUID scopeUnitId, LocalDate from, LocalDate to) {
        authorization.requireUnitPermission("MEMBERS_VIEW", scopeUnitId);
        if (from == null || to == null || from.isAfter(to))
            throw new IllegalArgumentException("A valid reporting date range is required.");
        return jdbc.query("""
            SELECT name, level FROM organisational_units
            WHERE id = ? AND organisation_id = ? AND active = TRUE
            """, (rs,n) -> new ReportContext(rs.getString("name"), rs.getString("level")),
            scopeUnitId, organisationId).stream().findFirst().orElseThrow(() ->
                new ResourceNotFoundException("Reporting scope was not found in this organisation."));
    }

    private static void setRow(XWPFTableRow row, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i >= row.getTableCells().size()) row.addNewTableCell();
            row.getCell(i).setText(values[i] == null ? "" : values[i]);
        }
    }

    public record ReportUnit(String id, String level, String code, String name) {}
    public record ReportContext(String unitName, String unitLevel) {}
    public record MemberLine(String firstName, String lastName, String zccAppNumber, boolean active,
        LocalDate registeredOn, String classification, String cellCode, String cellName) {}
    public record AppointmentLine(String firstName, String lastName, String zccAppNumber,
        String positionCode, String positionName, LocalDate validFrom, LocalDate validTo,
        String appointmentLevel, String appointmentScope, String note) {}
}
