package com.homestay.pms_backend.security.authorization;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Step 3.4.3.5B: Global Catalog Authorization Guard Unit Tests")
class GlobalCatalogAuthorizationGuardUnitTest {

    @Mock
    private StaffAuthorizationService authorizationService;

    @Mock
    private SecurityEnforcementProperties enforcementProperties;

    private GlobalCatalogAuthorizationGuard guard;

    @BeforeEach
    void setUp() {
        guard = new GlobalCatalogAuthorizationGuard(authorizationService, enforcementProperties);
    }

    @Test
    @DisplayName("Constructor enforces non-null dependencies")
    void constructorEnforcesNonNullDependencies() {
        assertThatThrownBy(() -> new GlobalCatalogAuthorizationGuard(null, enforcementProperties))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StaffAuthorizationService must not be null");

        assertThatThrownBy(() -> new GlobalCatalogAuthorizationGuard(authorizationService, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SecurityEnforcementProperties must not be null");
    }

    @Test
    @DisplayName("isEnforcementEnabled reflects underlying properties configuration")
    void isEnforcementEnabledReflectsProperties() {
        when(enforcementProperties.isEnabled()).thenReturn(true);
        assertThat(guard.isEnforcementEnabled()).isTrue();

        when(enforcementProperties.isEnabled()).thenReturn(false);
        assertThat(guard.isEnforcementEnabled()).isFalse();
    }

    @Test
    @DisplayName("When enforcement disabled, mutation passes without checking authorization service")
    void whenEnforcementDisabled_mutationPasses() {
        when(enforcementProperties.isEnabled()).thenReturn(false);

        assertThatNoException().isThrownBy(() -> guard.requireGlobalSuperAdminForMutation());
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("When enforcement enabled, global SUPER_ADMIN successfully passes mutation guard")
    void whenEnforcementEnabled_superAdminPasses() {
        when(enforcementProperties.isEnabled()).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(true);

        assertThatNoException().isThrownBy(() -> guard.requireGlobalSuperAdminForMutation());
        verify(authorizationService).isGlobalSuperAdmin();
    }

    @Test
    @DisplayName("When enforcement enabled, non-SUPER_ADMIN is denied with 403 AccessDeniedException")
    void whenEnforcementEnabled_nonSuperAdminDenied() {
        when(enforcementProperties.isEnabled()).thenReturn(true);
        when(authorizationService.isGlobalSuperAdmin()).thenReturn(false);

        assertThatThrownBy(() -> guard.requireGlobalSuperAdminForMutation())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access is denied.");

        verify(authorizationService).isGlobalSuperAdmin();
    }
}
