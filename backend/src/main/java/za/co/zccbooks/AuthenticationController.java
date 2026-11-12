package za.co.zccbooks;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final JdbcTemplate jdbc;

    public AuthenticationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/me")
    public CurrentAdministrator me(Authentication authentication) {
        return jdbc.query("""
            SELECT u.display_name,
                   COALESCE(array_agg(DISTINCT p.permission_code)
                       FILTER (WHERE p.permission_code IS NOT NULL), ARRAY[]::VARCHAR[]) AS permissions
            FROM admin_users u
            LEFT JOIN admin_role_assignments a ON a.user_id = u.id AND a.revoked_at IS NULL AND a.scope_type IN ('GLOBAL', 'ORGANISATION')
            LEFT JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            LEFT JOIN admin_permissions p ON p.permission_code = rp.permission_code
            WHERE LOWER(u.username) = LOWER(?)
            GROUP BY u.id, u.display_name
            """, (rs, n) -> new CurrentAdministrator(authentication.getName(), rs.getString("display_name"),
                List.of((String[]) rs.getArray("permissions").getArray())), authentication.getName())
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Administrator account not found."));
    }

    public record CurrentAdministrator(String username, String displayName, List<String> permissions) {}
}
