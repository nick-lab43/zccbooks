package za.co.zccbooks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuthorizationService authorization;

    public AdminController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.authorization = authorization;
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

    @PostMapping("/roles")
    public ResponseEntity<RoleDto> createRole(@Valid @RequestBody CreateRole request) {
        authorization.requireGlobalPermission("ROLE_CONFIGURE");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_roles (id, role_code, name, description, system_role) VALUES (?, ?, ?, ?, FALSE)",
            id, request.code().trim().toUpperCase(), request.name().trim(), blankToNull(request.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new RoleDto(id.toString(),
            request.code().trim().toUpperCase(), request.name().trim(), request.description(), false, new String[0]));
    }

    @PutMapping("/roles/{roleId}/permissions")
    public ResponseEntity<Void> setRolePermissions(@PathVariable UUID roleId,
            @Valid @RequestBody SetRolePermissions request) {
        authorization.requireGlobalPermission("ROLE_CONFIGURE");
        Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_roles WHERE id = ? AND system_role = FALSE", Integer.class, roleId);
        if (roleCount == null || roleCount == 0) throw new IllegalArgumentException("System roles cannot be reconfigured. Create a custom role instead.");
        if (roleCount == null || roleCount == 0) throw new ResourceNotFoundException("Role not found.");
        jdbc.update("DELETE FROM admin_role_permissions WHERE role_id = ?", roleId);
        for (String permission : request.permissionCodes().stream().distinct().toList()) {
            Integer permissionCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_permissions WHERE permission_code = ?",
                Integer.class, permission);
            if (permissionCount == null || permissionCount == 0) throw new IllegalArgumentException("Unknown permission: " + permission);
            jdbc.update("INSERT INTO admin_role_permissions (role_id, permission_code) VALUES (?, ?)", roleId, permission);
        }
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

    @PostMapping("/users")
    public ResponseEntity<AdminUserDto> createUser(@Valid @RequestBody CreateAdminUser request) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admin_users (id, username, display_name, password_hash)
            VALUES (?, ?, ?, ?)
            """, id, request.username().trim(), request.displayName().trim(), passwordEncoder.encode(request.password()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new AdminUserDto(id.toString(),
            request.username().trim(), request.displayName().trim(), true, null, ""));
    }

    @PatchMapping("/users/{userId}/enabled")
    public ResponseEntity<Void> setUserEnabled(@PathVariable UUID userId, @Valid @RequestBody SetEnabled request) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        int updated = jdbc.update("UPDATE admin_users SET enabled = ? WHERE id = ?", request.enabled(), userId);
        if (updated == 0) throw new ResourceNotFoundException("Administrator account not found.");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/assignments")
    public ResponseEntity<Void> assignRole(@PathVariable UUID userId, @Valid @RequestBody CreateAssignment request) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users WHERE id = ?", Integer.class, userId);
        Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_roles WHERE id = ?", Integer.class, request.roleId());
        if (userCount == null || userCount == 0) throw new ResourceNotFoundException("Administrator account not found.");
        if (roleCount == null || roleCount == 0) throw new ResourceNotFoundException("Role not found.");
        if ("GLOBAL".equals(request.scopeType())) {
            if (request.organisationId() != null || request.scopeUnitId() != null) throw new IllegalArgumentException("Global assignments cannot specify an organisation or unit.");
        } else if ("ORGANISATION".equals(request.scopeType())) {
            if (request.organisationId() == null || request.scopeUnitId() != null) throw new IllegalArgumentException("Organisation assignments require an organisation only.");
        } else if ("UNIT".equals(request.scopeType())) {
            if (request.organisationId() == null || request.scopeUnitId() == null) throw new IllegalArgumentException("Unit assignments require an organisation and unit.");
            Integer unitCount = jdbc.queryForObject("SELECT COUNT(*) FROM organisational_units WHERE id = ? AND organisation_id = ?",
                Integer.class, request.scopeUnitId(), request.organisationId());
            if (unitCount == null || unitCount == 0) throw new IllegalArgumentException("The selected unit does not belong to the selected organisation.");
        } else {
            throw new IllegalArgumentException("Scope type must be GLOBAL, ORGANISATION or UNIT.");
        }
        jdbc.update("""
            INSERT INTO admin_role_assignments (id, user_id, role_id, scope_type, organisation_id, scope_unit_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), userId, request.roleId(), request.scopeType(),
            request.organisationId(), request.scopeUnitId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/assignments/{assignmentId}")
    public ResponseEntity<Void> removeAssignment(@PathVariable UUID assignmentId) {
        authorization.requireGlobalPermission("ADMIN_MANAGE");
        int deleted = jdbc.update("UPDATE admin_role_assignments SET revoked_at = CURRENT_TIMESTAMP WHERE id = ? AND revoked_at IS NULL", assignmentId);
        if (deleted == 0) throw new ResourceNotFoundException("Active role assignment not found.");
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
}
