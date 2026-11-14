package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MemberAppointmentController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public MemberAppointmentController(JdbcTemplate jdbc, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/appointments/positions")
    public List<PositionDto> positions() {
        authorization.requireAnyOrganisationPermission("MEMBERS_VIEW");
        return jdbc.query("""
            SELECT id, code, name, description, allow_concurrent, active
            FROM church_positions
            WHERE active = TRUE
            ORDER BY name
            """, (rs, n) -> new PositionDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("allow_concurrent"), rs.getBoolean("active")));
    }

    @GetMapping("/appointments/positions/management-access")
    public boolean positionManagementAccess() {
        authorization.requireAnyOrganisationPermission("MEMBERS_VIEW");
        return authorization.isCentralAdministrator();
    }

    @PostMapping("/appointments/positions")
    @Transactional
    public ResponseEntity<PositionDto> createPosition(@Valid @RequestBody CreatePosition request) {
        authorization.requireCentralAdministrator();
        String code = request.code().trim().toUpperCase();
        Integer duplicate = jdbc.queryForObject(
            "SELECT COUNT(*) FROM church_positions WHERE LOWER(code) = LOWER(?)", Integer.class, code);
        if (duplicate != null && duplicate > 0) throw new ConflictException("A position with this code already exists.");
        UUID id = UUID.randomUUID();
        PositionDto position = jdbc.queryForObject("""
            INSERT INTO church_positions (id, code, name, description, allow_concurrent)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id, code, name, description, allow_concurrent, active
            """, (rs, n) -> new PositionDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("allow_concurrent"), rs.getBoolean("active")),
            id, code, request.name().trim(), blankToNull(request.description()), request.allowConcurrent());
        audit.record("CHURCH_POSITION_CREATED", "CHURCH_POSITION", id,
            Map.of("code", code, "name", request.name().trim(), "allowConcurrent", request.allowConcurrent()));
        return ResponseEntity.status(HttpStatus.CREATED).body(position);
    }

    @PatchMapping("/appointments/positions/{positionId}")
    @Transactional
    public PositionDto updatePosition(@PathVariable UUID positionId, @Valid @RequestBody UpdatePosition request) {
        authorization.requireCentralAdministrator();
        PositionDto existing = requirePosition(positionId);
        jdbc.update("""
            UPDATE church_positions
            SET name = ?, description = ?, allow_concurrent = ?, active = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, request.name().trim(), blankToNull(request.description()), request.allowConcurrent(), request.active(), positionId);
        audit.record("CHURCH_POSITION_UPDATED", "CHURCH_POSITION", positionId,
            Map.of("previousActive", existing.active(), "active", request.active(),
                "allowConcurrent", request.allowConcurrent(), "name", request.name().trim()));
        return requirePosition(positionId);
    }

    @GetMapping("/members/{memberId}/appointments")
    public List<AppointmentDto> memberAppointments(@PathVariable UUID memberId) {
        MemberRow member = requireMember(memberId);
        authorization.requireUnitPermission("MEMBERS_VIEW", member.cellId());
        return appointmentsFor(memberId);
    }

    @PostMapping("/members/{memberId}/appointments")
    @Transactional
    public ResponseEntity<AppointmentDto> createAppointment(@PathVariable UUID memberId,
            @Valid @RequestBody CreateAppointment request) {
        MemberRow member = requireMemberForUpdate(memberId);
        if (!member.active()) throw new ConflictException("Appointments cannot be assigned to an inactive member.");
        if (member.cellId() == null) {
            throw new ConflictException("Appointments require a member assigned to a geographical Cell.");
        }
        authorization.requireUnitPermission("MEMBERS_MANAGE", member.cellId());

        UnitRow scope = requireUnit(request.scopeUnitId());
        if (!member.organisationId().equals(scope.organisationId())) {
            throw new IllegalArgumentException("Appointment scope must belong to the member's organisation.");
        }
        authorization.requireUnitPermission("MEMBERS_MANAGE", scope.id());
        if (!List.of("NYIKA", "TABHERA", "CELL").contains(scope.level()) || !scope.active()) {
            throw new IllegalArgumentException("Appointment scope must be an active Nyika, Tabhera or Cell.");
        }
        Integer descendant = jdbc.queryForObject("""
            WITH RECURSIVE ancestors AS (
                SELECT id, parent_id, organisation_id
                FROM organisational_units
                WHERE id = ?
                UNION ALL
                SELECT parent.id, parent.parent_id, parent.organisation_id
                FROM organisational_units parent
                JOIN ancestors child
                  ON child.parent_id = parent.id
                 AND child.organisation_id = parent.organisation_id
            )
            SELECT COUNT(*) FROM ancestors WHERE id = ?
            """, Integer.class, member.cellId(), scope.id());
        if (descendant == null || descendant == 0) {
            throw new IllegalArgumentException("Appointment scope must be the member's Cell or one of its ancestors.");
        }

        PositionDto position = requirePosition(request.positionId());
        if (!position.active()) throw new ConflictException("This position is inactive and cannot receive new appointments.");
        LocalDate start = request.validFrom();
        LocalDate end = request.validTo();
        if (end != null && end.isBefore(start)) throw new IllegalArgumentException("End date cannot be before start date.");

        String overlapSql = """
            SELECT COUNT(*) FROM member_appointments
            WHERE organisation_id = ? AND member_id = ? AND position_id = ?
              AND valid_from <= COALESCE(?, DATE '9999-12-31')
              AND COALESCE(valid_to, DATE '9999-12-31') >= ?
            """ + (position.allowConcurrent() ? " AND scope_unit_id = ?" : "");
        Object[] overlapArgs = position.allowConcurrent()
            ? new Object[] { member.organisationId(), member.id(), request.positionId(), end == null ? null : Date.valueOf(end), Date.valueOf(start), scope.id() }
            : new Object[] { member.organisationId(), member.id(), request.positionId(), end == null ? null : Date.valueOf(end), Date.valueOf(start) };
        Integer overlap = jdbc.queryForObject(overlapSql, Integer.class, overlapArgs);
        if (overlap != null && overlap > 0) {
            throw new ConflictException(position.allowConcurrent()
                ? "This member already has an overlapping appointment to this position at the selected scope."
                : "This member already has an overlapping appointment to this position.");
        }

        UUID actorId = jdbc.queryForObject(
            "SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authorization.currentUsername());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO member_appointments
                (id, organisation_id, member_id, position_id, scope_unit_id, valid_from, valid_to, note, recorded_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, member.organisationId(), member.id(), request.positionId(), scope.id(),
            Date.valueOf(start), end == null ? null : Date.valueOf(end), blankToNull(request.note()), actorId);
        audit.record("MEMBER_APPOINTMENT_CREATED", "MEMBER_APPOINTMENT", id,
            Map.of("memberId", member.id().toString(), "positionId", request.positionId().toString(),
                "scopeUnitId", scope.id().toString(), "validFrom", start.toString(),
                "validTo", end == null ? "" : end.toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(appointmentsFor(memberId).stream()
            .filter(row -> row.id().equals(id.toString())).findFirst().orElseThrow());
    }

    @PatchMapping("/appointments/{appointmentId}/end")
    @Transactional
    public ResponseEntity<Void> endAppointment(@PathVariable UUID appointmentId,
            @Valid @RequestBody EndAppointment request) {
        AppointmentRow row = jdbc.query("""
            SELECT a.id, a.member_id, a.organisation_id, a.scope_unit_id, a.valid_from, a.valid_to, m.cell_id
            FROM member_appointments a
            JOIN members m ON m.id = a.member_id AND m.organisation_id = a.organisation_id
            WHERE a.id = ?
            FOR UPDATE OF a
            """, (rs, n) -> new AppointmentRow(
                rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("scope_unit_id", UUID.class),
                rs.getDate("valid_from").toLocalDate(),
                rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate(),
                rs.getObject("cell_id", UUID.class)), appointmentId).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found."));
        authorization.requireUnitPermission("MEMBERS_MANAGE", row.cellId());
        authorization.requireUnitPermission("MEMBERS_MANAGE", row.scopeUnitId());
        if (row.validTo() != null) throw new ConflictException("This appointment has already ended.");
        if (request.validTo().isBefore(row.validFrom())) throw new IllegalArgumentException("End date cannot be before the appointment start date.");
        jdbc.update("UPDATE member_appointments SET valid_to = ? WHERE id = ? AND valid_to IS NULL",
            Date.valueOf(request.validTo()), appointmentId);
        audit.record("MEMBER_APPOINTMENT_ENDED", "MEMBER_APPOINTMENT", appointmentId,
            Map.of("memberId", row.memberId().toString(), "scopeUnitId", row.scopeUnitId().toString(),
                "validTo", request.validTo().toString()));
        return ResponseEntity.noContent().build();
    }

    private List<AppointmentDto> appointmentsFor(UUID memberId) {
        return jdbc.query("""
            SELECT a.id, a.position_id, p.code AS position_code, p.name AS position_name,
                   a.scope_unit_id, u.level AS scope_level, u.name AS scope_name,
                   a.valid_from, a.valid_to, a.note
            FROM member_appointments a
            JOIN church_positions p ON p.id = a.position_id
            JOIN organisational_units u ON u.id = a.scope_unit_id AND u.organisation_id = a.organisation_id
            WHERE a.member_id = ?
            ORDER BY a.valid_from DESC, p.name
            """, (rs, n) -> new AppointmentDto(
                rs.getObject("id", UUID.class).toString(), rs.getObject("position_id", UUID.class).toString(),
                rs.getString("position_code"), rs.getString("position_name"),
                rs.getObject("scope_unit_id", UUID.class).toString(), rs.getString("scope_level"),
                rs.getString("scope_name"), rs.getDate("valid_from").toLocalDate().toString(),
                rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate().toString(),
                rs.getString("note")), memberId);
    }

    private MemberRow requireMember(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, cell_id, active
            FROM members WHERE id = ?
            """, (rs, n) -> new MemberRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("cell_id", UUID.class),
                rs.getBoolean("active")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Member not found."));
    }

    private MemberRow requireMemberForUpdate(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, cell_id, active
            FROM members WHERE id = ? FOR UPDATE
            """, (rs, n) -> new MemberRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getObject("cell_id", UUID.class),
                rs.getBoolean("active")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Member not found."));
    }

    private UnitRow requireUnit(UUID id) {
        return jdbc.query("""
            SELECT id, organisation_id, level, active
            FROM organisational_units WHERE id = ?
            """, (rs, n) -> new UnitRow(rs.getObject("id", UUID.class),
                rs.getObject("organisation_id", UUID.class), rs.getString("level"),
                rs.getBoolean("active")), id).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Geographical unit not found."));
    }

    private PositionDto requirePosition(UUID id) {
        return jdbc.query("""
            SELECT id, code, name, description, allow_concurrent, active
            FROM church_positions WHERE id = ?
            """, (rs, n) -> new PositionDto(
                rs.getObject("id", UUID.class).toString(), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("allow_concurrent"), rs.getBoolean("active")),
            id).stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Church position not found."));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record CreatePosition(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,60}") String code,
        @NotBlank @Size(max = 120) String name, @Size(max = 300) String description,
        boolean allowConcurrent) {}
    public record UpdatePosition(@NotBlank @Size(max = 120) String name, @Size(max = 300) String description,
        boolean allowConcurrent, boolean active) {}
    public record CreateAppointment(@NotNull UUID positionId, @NotNull UUID scopeUnitId,
        @NotNull LocalDate validFrom, LocalDate validTo, @Size(max = 1000) String note) {}
    public record EndAppointment(@NotNull LocalDate validTo) {}
    public record PositionDto(String id, String code, String name, String description,
        boolean allowConcurrent, boolean active) {}
    public record AppointmentDto(String id, String positionId, String positionCode, String positionName,
        String scopeUnitId, String scopeLevel, String scopeName, String validFrom, String validTo, String note) {}
    private record MemberRow(UUID id, UUID organisationId, UUID cellId, boolean active) {}
    private record UnitRow(UUID id, UUID organisationId, String level, boolean active) {}
    private record AppointmentRow(UUID id, UUID memberId, UUID organisationId, UUID scopeUnitId,
        LocalDate validFrom, LocalDate validTo, UUID cellId) {}
}
