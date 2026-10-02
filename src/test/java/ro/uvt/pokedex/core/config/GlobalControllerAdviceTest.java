package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ro.uvt.pokedex.core.model.user.User;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalControllerAdviceTest {

    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<ro.uvt.pokedex.core.service.security.ArtisticEventAccessService> eventAccess =
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    private final GlobalControllerAdvice advice = new GlobalControllerAdvice(eventAccess);

    @Test
    void theArtisticEventsPageIsOfferedOnPageLoadsToPeopleWhoRankEvents() {
        var access = org.mockito.Mockito.mock(ro.uvt.pokedex.core.service.security.ArtisticEventAccessService.class);
        org.mockito.Mockito.when(eventAccess.getIfAvailable()).thenReturn(access);
        User user = new User();
        user.setEmail("dean@uvt.ro");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));
        org.mockito.Mockito.when(access.canReviewAny(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var page = new org.springframework.mock.web.MockHttpServletRequest("GET", "/user/workspace");
        assertTrue(advice.canRankArtisticEvents(page));
        org.junit.jupiter.api.Assertions.assertFalse(advice.canRankArtisticEvents(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/entities/forums")), "not for API calls");
        org.junit.jupiter.api.Assertions.assertFalse(advice.canRankArtisticEvents(
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/user/workspace")), "not for form posts");
        org.mockito.Mockito.when(eventAccess.getIfAvailable()).thenReturn(null);
        org.junit.jupiter.api.Assertions.assertFalse(advice.canRankArtisticEvents(page), "web-slice tests have no access service");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUserReturnsEmptyOptionalWhenUnauthenticated() {
        assertTrue(advice.currentUser().isEmpty());
    }

    @Test
    void currentUserReturnsOptionalWhenAuthenticatedWithUserPrincipal() {
        User user = new User();
        user.setEmail("user@uvt.ro");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));

        assertTrue(advice.currentUser().isPresent());
    }

    private static org.springframework.mock.web.MockHttpServletRequest at(String path) {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private static void signIn(java.util.Set<ro.uvt.pokedex.core.model.user.UserRole> roles, boolean head) {
        User user = new User();
        user.setEmail("someone@uvt.ro");
        user.setRoles(new java.util.HashSet<>(roles));
        user.setSupervisorByPosition(head);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, user.getAuthorities().stream().toList()));
    }

    @Test
    void aHeadOnTheReportPagesOfTheirUnitKeepsTheUserSidebar() {
        // The admin sidebar is a list of pages a head cannot open.
        signIn(java.util.Set.of(ro.uvt.pokedex.core.model.user.UserRole.RESEARCHER), true);

        org.junit.jupiter.api.Assertions.assertEquals("user",
                advice.sidebarContext(at("/admin/departments/dept-psy/reports/r1")));
        org.junit.jupiter.api.Assertions.assertEquals("user", advice.sidebarContext(at("/admin/groups/g1")));
    }

    @Test
    void aPlatformAdminKeepsTheAdminSidebarUnderAdmin() {
        signIn(java.util.Set.of(ro.uvt.pokedex.core.model.user.UserRole.PLATFORM_ADMIN,
                ro.uvt.pokedex.core.model.user.UserRole.RESEARCHER), false);

        org.junit.jupiter.api.Assertions.assertEquals("admin",
                advice.sidebarContext(at("/admin/departments/dept-psy/reports/r1")));
        org.junit.jupiter.api.Assertions.assertEquals("admin", advice.sidebarContext(at("/admin")));
    }

    @Test
    void withoutASignedInUserTheAdminPathsKeepTheirContext() {
        org.junit.jupiter.api.Assertions.assertEquals("admin", advice.sidebarContext(at("/admin/users")));
    }
}
