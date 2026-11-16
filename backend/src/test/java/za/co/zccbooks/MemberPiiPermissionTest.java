package za.co.zccbooks;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MemberPiiPermissionTest {
    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final AuthorizationService authorization = new AuthorizationService(jdbc);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void membershipReadPermissionDoesNotGrantPersonalInformationAccess() {
        UUID cellId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("auditor", "not-used", List.of()));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(cellId),
                eq("auditor"), eq("MEMBER_PII_VIEW"))).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertThrows(AccessDeniedException.class,
            () -> authorization.requireUnitPermission("MEMBER_PII_VIEW", cellId));
    }

    @Test
    void personalInformationPermissionIsCheckedAgainstAssignedUnitScope() {
        UUID cellId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("cell.manager", "not-used", List.of()));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(cellId),
                eq("cell.manager"), eq("MEMBER_PII_VIEW"))).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> authorization.requireUnitPermission("MEMBER_PII_VIEW", cellId));
    }
}
