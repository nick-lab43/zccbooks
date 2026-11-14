package za.co.zccbooks;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
    private final JdbcTemplate jdbc;

    public AuthorizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireGlobalPermission(String permission) {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            WHERE a.revoked_at IS NULL AND a.user_id = (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?))
              AND a.scope_type = 'GLOBAL' AND rp.permission_code = ?
            """, Integer.class, username, permission);
        if (count == null || count == 0) throw new AccessDeniedException("Permission denied.");
    }


    public void requireAnyOrganisationPermission(String permission) {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            WHERE a.revoked_at IS NULL
              AND a.user_id = (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?))
              AND rp.permission_code = ?
              AND a.scope_type IN ('GLOBAL', 'ORGANISATION', 'UNIT')
            """, Integer.class, username, permission);
        if (count == null || count == 0) throw new AccessDeniedException("Permission denied.");
    }

    public boolean canAccessOrganisation(String permission, UUID organisationId) {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            WHERE a.revoked_at IS NULL
              AND a.user_id = (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?))
              AND rp.permission_code = ?
              AND (a.scope_type = 'GLOBAL'
                   OR (a.scope_type IN ('ORGANISATION', 'UNIT') AND a.organisation_id = ?))
            """, Integer.class, username, permission, organisationId);
        return count != null && count > 0;
    }

    public boolean hasOrganisationWidePermission(String permission, UUID organisationId) {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            WHERE a.revoked_at IS NULL
              AND a.user_id = (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?))
              AND rp.permission_code = ?
              AND (a.scope_type = 'GLOBAL'
                   OR (a.scope_type = 'ORGANISATION' AND a.organisation_id = ?))
            """, Integer.class, username, permission, organisationId);
        return count != null && count > 0;
    }

    public void requireOrganisationOrUnitPermission(String permission, UUID organisationId) {
        if (!canAccessOrganisation(permission, organisationId)) {
            throw new AccessDeniedException("Permission denied for this organisation.");
        }
    }


    public boolean isCentralAdministrator() {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM admin_role_assignments a
            JOIN admin_roles r ON r.id = a.role_id
            JOIN admin_role_permissions rp ON rp.role_id = r.id
            WHERE a.revoked_at IS NULL
              AND a.scope_type = 'GLOBAL'
              AND a.user_id = (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?))
              AND r.role_code = 'CENTRAL_ADMIN'
              AND rp.permission_code = 'ORG_MANAGE'
            """, Integer.class, username);
        return count != null && count > 0;
    }

    public void requireCentralAdministrator() {
        if (!isCentralAdministrator()) {
            throw new AccessDeniedException("Only Central Administration can manage this level of the hierarchy.");
        }
    }

    public void requireNyikaAdministratorFor(UUID parentUnitId) {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            WITH RECURSIVE ancestors AS (
                SELECT id, organisation_id, parent_id, level
                FROM organisational_units
                WHERE id = ?
                UNION ALL
                SELECT parent.id, parent.organisation_id, parent.parent_id, parent.level
                FROM organisational_units parent
                JOIN ancestors child
                  ON child.parent_id = parent.id
                 AND child.organisation_id = parent.organisation_id
            )
            SELECT COUNT(*)
            FROM ancestors nyika
            JOIN admin_role_assignments a
              ON a.scope_unit_id = nyika.id
             AND a.organisation_id = nyika.organisation_id
             AND a.scope_type = 'UNIT'
             AND a.revoked_at IS NULL
            JOIN admin_users u ON u.id = a.user_id
            JOIN admin_roles r ON r.id = a.role_id
            JOIN admin_role_permissions rp
              ON rp.role_id = r.id
             AND rp.permission_code = 'ORG_MANAGE'
            WHERE nyika.level = 'NYIKA'
              AND LOWER(u.username) = LOWER(?)
              AND r.role_code = 'NYIKA_ADMIN'
            """, Integer.class, parentUnitId, username);
        if (count == null || count == 0) {
            throw new AccessDeniedException("Only the assigned Nyika Administrator or Central Administration can manage Tabheras and Cells in this Nyika.");
        }
    }

    public String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required.");
        }
        return authentication.getName();
    }

    public void requireOrganisationPermission(String permission, UUID organisationId) {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            WHERE a.revoked_at IS NULL
              AND a.user_id = (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?))
              AND rp.permission_code = ?
              AND (a.scope_type = 'GLOBAL'
                   OR (a.scope_type = 'ORGANISATION' AND a.organisation_id = ?))
            """, Integer.class, username, permission, organisationId);
        if (count == null || count == 0) throw new AccessDeniedException("Permission denied for this organisation.");
    }

    public void requireUnitPermission(String permission, UUID unitId) {
        String username = currentUsername();
        Integer count = jdbc.queryForObject("""
            WITH RECURSIVE unit_path AS (
                SELECT id, organisation_id, parent_id
                FROM organisational_units WHERE id = ?
                UNION ALL
                SELECT parent.id, parent.organisation_id, parent.parent_id
                FROM organisational_units parent
                JOIN unit_path child ON child.parent_id = parent.id
                WHERE parent.organisation_id = child.organisation_id
            )
            SELECT COUNT(*)
            FROM admin_role_assignments a
            JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            CROSS JOIN unit_path path
            WHERE a.revoked_at IS NULL
              AND a.user_id = (SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?))
              AND rp.permission_code = ?
              AND (a.scope_type = 'GLOBAL'
                   OR (a.scope_type = 'ORGANISATION' AND a.organisation_id = path.organisation_id)
                   OR (a.scope_type = 'UNIT' AND a.scope_unit_id = path.id))
            """, Integer.class, unitId, username, permission);
        if (count == null || count == 0) throw new AccessDeniedException("Permission denied for this geographical scope.");
    }

}
