package za.co.zccbooks;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public AdminUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<UserDetails> users = jdbc.query("""
            SELECT u.username, u.password_hash, u.enabled,
                   COALESCE(array_agg(DISTINCT p.permission_code)
                       FILTER (WHERE p.permission_code IS NOT NULL), ARRAY[]::VARCHAR[]) AS permissions
            FROM admin_users u
            LEFT JOIN admin_role_assignments a ON a.user_id = u.id
            LEFT JOIN admin_role_permissions rp ON rp.role_id = a.role_id
            LEFT JOIN admin_permissions p ON p.permission_code = rp.permission_code
            WHERE LOWER(u.username) = LOWER(?)
            GROUP BY u.id, u.username, u.password_hash, u.enabled
            """, (rs, row) -> {
                String[] permissions = (String[]) rs.getArray("permissions").getArray();
                return User.withUsername(rs.getString("username"))
                    .password(rs.getString("password_hash"))
                    .disabled(!rs.getBoolean("enabled"))
                    .authorities(List.of(permissions))
                    .build();
            }, username);
        if (users.isEmpty()) throw new UsernameNotFoundException("Administrator account not found.");
        return users.get(0);
    }
}
