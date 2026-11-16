package za.co.zccbooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(String action, String targetType, UUID targetId, Map<String, ?> details) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return;
        UUID actorId = jdbc.queryForObject("SELECT id FROM admin_users WHERE LOWER(username) = LOWER(?)",
            UUID.class, authentication.getName());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (details != null) payload.putAll(details);
            payload.put("_metadata", metadata(actorId, authentication.getName(), payload));
            jdbc.update("""
                INSERT INTO admin_audit_events (id, actor_user_id, action_code, target_type, target_id, details)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), actorId, action, targetType, targetId,
                objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialise administrator audit details.", exception);
        }
    }

    private Map<String, Object> metadata(UUID actorId, String actorUsername, Map<String, Object> details) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("actorUserId", actorId.toString());
        metadata.put("actorUsername", actorUsername);

        Object organisationId = details.get("organisationId");
        if (organisationId != null) metadata.put("organisationId", organisationId.toString());

        Object scopeUnitId = details.get("scopeUnitId");
        if (scopeUnitId == null) scopeUnitId = details.get("cellId");
        if (scopeUnitId != null) metadata.put("scopeUnitId", scopeUnitId.toString());

        Object sourceCellId = details.get("sourceCellId");
        Object destinationCellId = details.get("destinationCellId");
        if (sourceCellId != null) metadata.put("sourceScopeUnitId", sourceCellId.toString());
        if (destinationCellId != null) metadata.put("destinationScopeUnitId", destinationCellId.toString());
        return metadata;
    }
}
