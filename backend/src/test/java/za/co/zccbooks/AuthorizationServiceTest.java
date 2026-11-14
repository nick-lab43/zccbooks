package za.co.zccbooks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AuthorizationServiceTest {
    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final AuthorizationService authorization = new AuthorizationService(jdbc);

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("central.operator", "not-used",
                List.of(new SimpleGrantedAuthority("ORG_VIEW"))));
    }

    @Test
    void globalPermissionRequiresAnActiveGlobalAssignment() {
        authenticate();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("central.operator"), eq("ORG_VIEW"))).thenReturn(1);
        assertDoesNotThrow(() -> authorization.requireGlobalPermission("ORG_VIEW"));
    }

    @Test
    void organisationPermissionDeniesAnUnassignedOrganisation() {
        authenticate();
        UUID organisationId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("central.operator"), eq("MEMBERS_VIEW"), eq(organisationId))).thenReturn(0);
        assertThrows(AccessDeniedException.class,
            () -> authorization.requireOrganisationPermission("MEMBERS_VIEW", organisationId));
    }


    @Test
    void unitScopedAdministratorCanDiscoverAssignedOrganisation() {
        authenticate();
        UUID organisationId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("central.operator"), eq("ORG_VIEW"), eq(organisationId))).thenReturn(1);
        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        assertDoesNotThrow(() -> authorization.requireOrganisationOrUnitPermission("ORG_VIEW", organisationId));
    }

    @Test
    void unitScopedAdministratorDoesNotReceiveOrganisationWideAccess() {
        authenticate();
        UUID organisationId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("central.operator"), eq("ORG_VIEW"), eq(organisationId))).thenReturn(0);
        assertThrows(AccessDeniedException.class,
            () -> authorization.requireOrganisationOrUnitPermission("ORG_VIEW", organisationId));
    }

    @Test
    void unitPermissionDeniesWhenNoAncestorScopeGrantsThePermission() {
        authenticate();
        UUID unitId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(unitId), eq("central.operator"), eq("MEMBERS_MANAGE"))).thenReturn(0);
        assertThrows(AccessDeniedException.class,
            () -> authorization.requireUnitPermission("MEMBERS_MANAGE", unitId));
    }

    @Test
    void centralAdministratorRequiresTheCentralRoleAndGlobalScope() {
        authenticate();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("central.operator"))).thenReturn(1);
        assertDoesNotThrow(() -> authorization.requireCentralAdministrator());
    }

    @Test
    void nonCentralAdministratorCannotManageCentralHierarchyLevels() {
        authenticate();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("central.operator"))).thenReturn(0);
        assertThrows(AccessDeniedException.class, () -> authorization.requireCentralAdministrator());
    }

    @Test
    void nyikaAdministratorMustBeAssignedToAnAncestorNyika() {
        authenticate();
        UUID parentUnitId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(parentUnitId), eq("central.operator"))).thenReturn(1);
        assertDoesNotThrow(() -> authorization.requireNyikaAdministratorFor(parentUnitId));
    }

    @Test
    void administratorWithoutMatchingNyikaAssignmentCannotManageLocalHierarchy() {
        authenticate();
        UUID parentUnitId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(parentUnitId), eq("central.operator"))).thenReturn(0);
        assertThrows(AccessDeniedException.class,
            () -> authorization.requireNyikaAdministratorFor(parentUnitId));
    }
}
