package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuthorizationService authorization;
    private final AdminAuditService audit;

    public AdminController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuthorizationService authorization, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/roles")
    public List<RoleDto> roles() {
        authorization.requireGlobalPermission("ROLE_CONFIGURE");
        return jdbc.query("""
            SELECT r.id, r.role_code, r.name, r.description, r.system_role,
                   COALESCE(array_agg(rp.permission_code) FILTER (WHERE rp.permission_code IS NOT NULL), ARRAY[]::VARCHAR[]) AS permissions
            FROM admin_roles r LEFT JOIN admin_role_permissions rp ON rp.role_id = r.id
            GROUP BY r.id ORDER BY r.name
            """, (rs, n) -> new RoleDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("role_code"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("system_role"), (String[]) rs.getArray("permissions").getArray()));
    }

    @GetMapping("/permissions")
    public List<PermissionDto> permissions() {
        authorization.requireGlobalPermission("ROLE_CONFIGURE");
        return jdbc.query("SELECT permission_code, name, description FROM admin_permissions ORDER BY permission_code",
            (rs, n) -> new PermissionDto(rs.getString("permission_code"), rs.getString("name"), rs.getString("description")));
    }

    @Transactional
    @PostMapping("/roles")
    public ResponseEntity<RoleDto> createRole(@Valid @RequestBody CreateRole request) {
        authorization.requireGlobalPermission("ROLE_CONFIGURE");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_roles (id, role_code, name, description, system_role) VALUES (?, ?, ?, ?, FALSE)",
            id, request.code().trim().toUpperCase(), request.name().trim(), blankToNull(request.description()));
        audit.record("ROLE_CREATED", "ROLE", id, Map.of("roleCode", request.code().trim().toUpperCase(), "name", request.name().trim()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new RoleDto(id.toString(),
            request.code().trim().toUpperCase(), request.name().trim(), request.description(), false, new String[0]));
    }

    @Transactional
    @PutMapping("/roles/{roleId}/permissions")
    public ResponseEntity<Void> setRolePermissions(@PathVariable UUID roleId,
            @Valid @RequestBody SetRolePermissions request) {
        authorization.requireGlobalPermission("ROLE_CONFIGURE");
        Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_roles WHERE id = ? AND system_role = FALSE", Integer.class, roleId);
        if (roleCount == null || roleCount == 0) throw new IllegalArgumentException("System roles cannot be reconfigured. Create a custom role instead.");
        List<String> permissionCodes = request.permissionCodes().stream().distinct().toList();
        for (String permission : permissionCodes) {
            Integer permissionCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_permissions WHERE permission_code = ?", Integer.class, permission);
            if (permissionCount == null || permissionCount == 0) throw new IllegalArgumentException("Unknown permission: " + permission);
        }
        jdbc.update("DELETE FROM admin_role_permissions WHERE role_id = ?", roleId);
        for (String permission : permissionCodes) {
            jdbc.update("INSERT INTO admin_role_permissions (role_id, permission_code) VALUES (?, ?)", roleId, permission);
        }
        audit.record("ROLE_PERMISSIONS_CHANGED", "ROLE", roleId, Map.of("permissionCodes", permissionCodes));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    public List<AdminUserDto> users() {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        return jdbc.query("""
            SELECT u.id, u.username, u.display_name, u.enabled, u.created_at,
                   COALESCE(string_agg(DISTINCT r.role_code || ':' || a.scope_type, ', '), '') AS assignments
            FROM admin_users u
            LEFT JOIN admin_role_assignments a ON a.user_id = u.id AND a.revoked_at IS NULL
            LEFT JOIN admin_roles r ON r.id = a.role_id
            WHERE a.revoked_at IS NULL OR a.id IS NULL
            GROUP BY u.id ORDER BY u.username
            """, (rs, n) -> new AdminUserDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("username"), rs.getString("display_name"), rs.getBoolean("enabled"),
                rs.getString("created_at"), rs.getString("assignments")));
    }

    @Transactional
    @PostMapping("/users")
    public ResponseEntity<AdminUserDto> createUser(@Valid @RequestBody CreateAdminUser request) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash)
            VALUES (?, ?, ?, ?)
            """, id, request.username().trim(), request.displayName().trim(), passwordEncoder.encode(request.password()));
        audit.record("ADMIN_CREATED", "ADMIN_USER", id, Map.of("username", request.username().trim()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new AdminUserDto(id.toString(),
            request.username().trim(), request.displayName().trim(), true, null, ""));
    }

    @Transactional
    @PatchMapping("/users/{userId}/enabled")
    public ResponseEntity<Void> setUserEnabled(@PathVariable UUID userId, @Valid @RequestBody SetEnabled request) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        if (!request.enabled()) {
            Integer targetIsCentral = jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_role_assignments a JOIN admin_roles r ON r.id = a.role_id
                WHERE a.user_id = ? AND r.role_code = 'CENTRAL_ADMIN' AND a.scope_type = 'GLOBAL' AND a.revoked_at IS NULL
                """, Integer.class, userId);
            Integer enabledCentralAdmins = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT a.user_id) FROM admin_role_assignments a
                JOIN admin_roles r ON r.id = a.role_id JOIN admin_users u ON u.id = a.user_id
                WHERE r.role_code = 'CENTRAL_ADMIN' AND a.scope_type = 'GLOBAL' AND a.revoked_at IS NULL AND u.enabled = TRUE
                """, Integer.class);
            if (targetIsCentral != null && targetIsCentral > 0 && enabledCentralAdmins != null && enabledCentralAdmins <= 1)
                throw new IllegalArgumentException("The last enabled central administrator cannot be disabled.");
        }
        int updated = jdbc.update("UPDATE admin_users SET enabled = ? WHERE id = ?", request.enabled(), userId);
        if (updated == 0) throw new ResourceNotFoundException("Administrator account not found.");
        audit.record(request.enabled() ? "ADMIN_ENABLED" : "ADMIN_DISABLED", "ADMIN_USER", userId, Map.of());
        return ResponseEntity.noContent().build();
    }

    @Transactional
    @PostMapping("/users/{userId}/assignments")
    public ResponseEntity<Void> assignRole(@PathVariable UUID userId, @Valid @RequestBody CreateAssignment request) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users WHERE id = ?", Integer.class, userId);
        Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_roles WHERE id = ?", Integer.class, request.roleId());
        if (userCount == null || userCount == 0) throw new ResourceNotFoundException("Administrator account not found.");
        if (roleCount == null || roleCount == 0) throw new ResourceNotFoundException("Role not found.");
        String scopeType = request.scopeType().trim().toUpperCase();
        if ("GLOBAL".equals(scopeType)) {
            if (request.organisationId() != null || request.scopeUnitId() != null) throw new IllegalArgumentException("Global assignments cannot specify an organisation or unit.");
        } else if ("ORGANISATION".equals(scopeType)) {
            if (request.organisationId() == null || request.scopeUnitId() != null) throw new IllegalArgumentException("Organisation assignments require an organisation only.");
        } else if ("UNIT".equals(scopeType)) {
            if (request.organisationId() == null || request.scopeUnitId() == null) throw new IllegalArgumentException("Unit assignments require an organisation and unit.");
            Integer unitCount = jdbc.queryForObject("SELECT COUNT(*) FROM organisational_units WHERE id = ? AND organisation_id = ?",
                Integer.class, request.scopeUnitId(), request.organisationId());
            if (unitCount == null || unitCount == 0) throw new IllegalArgumentException("The selected unit does not belong to the selected organisation.");
        } else {
            throw new IllegalArgumentException("Scope type must be GLOBAL, ORGANISATION or UNIT.");
        }
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, assignmentId, userId, request.roleId(), scopeType,
            request.organisationId(), request.scopeUnitId());
        audit.record("ROLE_ASSIGNED", "ROLE_ASSIGNMENT", assignmentId, Map.of("userId", userId.toString(), "roleId", request.roleId().toString(), "scopeType", scopeType));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Transactional
    @GetMapping("/audit")
    public List<AuditEventDto> auditEvents() {
        authorization.requireGlobalPermission("AUDIT_VIEW");
        return jdbc.query("""
            SELECT e.id, u.username, e.action_code, e.target_type, e.target_id,
                   e.details::text AS details, e.occurred_at::text AS occurred_at
            FROM admin_audit_events e JOIN admin_users u ON u.id = e.actor_user_id
            ORDER BY e.occurred_at DESC LIMIT 250
            """, (rs, n) -> new AuditEventDto(rs.getObject("id", UUID.class).toString(),
                rs.getString("username"), rs.getString("action_code"), rs.getString("target_type"),
                rs.getObject("target_id") == null ? null : rs.getObject("target_id", UUID.class).toString(),
                rs.getString("details"), rs.getString("occurred_at")));
    }

    @Transactional
    @DeleteMapping("/assignments/{assignmentId}")
    public ResponseEntity<Void> removeAssignment(@PathVariable UUID assignmentId) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        Integer targetIsCentral = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_role_assignments a JOIN admin_roles r ON r.id = a.role_id
            WHERE a.id = ? AND r.role_code = 'CENTRAL_ADMIN' AND a.scope_type = 'GLOBAL' AND a.revoked_at IS NULL
            """, Integer.class, assignmentId);
        Integer allCentralAdmins = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT a.user_id) FROM admin_role_assignments a
            JOIN admin_roles r ON r.id = a.role_id JOIN admin_users u ON u.id = a.user_id
            WHERE r.role_code = 'CENTRAL_ADMIN' AND a.scope_type = 'GLOBAL' AND a.revoked_at IS NULL AND u.enabled = TRUE
            """, Integer.class);
        if (targetIsCentral != null && targetIsCentral > 0 && allCentralAdmins != null && allCentralAdmins <= 1)
            throw new IllegalArgumentException("The last enabled central administrator assignment cannot be revoked.");
        int deleted = jdbc.update("UPDATE admin_role_assignments SET revoked_at = CURRENT_TIMESTAMP WHERE id = ? AND revoked_at IS NULL", assignmentId);
        if (deleted == 0) throw new ResourceNotFoundException("Active role assignment not found.");
        audit.record("ROLE_ASSIGNMENT_REVOKED", "ROLE_ASSIGNMENT", assignmentId, Map.of());
        return ResponseEntity.noContent().build();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record CreateRole(@NotBlank @Size(max = 60) String code, @NotBlank @Size(max = 120) String name,
                             @Size(max = 300) String description) {}
    public record SetRolePermissions(@NotNull List<String> permissionCodes) {}
    public record CreateAdminUser(@NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 160) String displayName,
                                  @NotBlank @Size(min = 12, max = 100) String password) {}
    public record SetEnabled(@NotNull Boolean enabled) {}
    public record CreateAssignment(@NotNull UUID roleId, @NotBlank String scopeType,
                                   UUID organisationId, UUID scopeUnitId) {}
    public record RoleDto(String id, String code, String name, String description, boolean systemRole, String[] permissions) {}
    public record PermissionDto(String code, String name, String description) {}
    public record AdminUserDto(String id, String username, String displayName, boolean enabled, String createdAt, String assignments) {}
    public record AuditEventDto(String id, String actor, String action, String targetType, String targetId, String details, String occurredAt) {}
}
