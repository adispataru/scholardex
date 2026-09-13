package ro.uvt.pokedex.core.model.user;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** H105 S2: an EXTERNAL account can only ever act as a RESEARCHER; pre-H105 documents are INSTITUTIONAL. */
class UserAccountKindTest {

    @Test
    void defaultsToInstitutionalAndKeepsEveryRole() {
        User u = new User();
        u.setEmail("staff@e-uvt.ro");
        u.setRoles(Set.of(UserRole.RESEARCHER, UserRole.SUPERVISOR, UserRole.PLATFORM_ADMIN));
        assertFalse(u.isExternal());
        assertEquals(Set.of("RESEARCHER", "SUPERVISOR", "PLATFORM_ADMIN"), authorities(u));
    }

    @Test
    void externalAccountDropsSupervisorAndAdminAuthorities() {
        User u = new User();
        u.setEmail("cand@ext.ro");
        u.setAccountKind(AccountKind.EXTERNAL);
        u.setRoles(Set.of(UserRole.RESEARCHER, UserRole.SUPERVISOR, UserRole.PLATFORM_ADMIN));
        assertEquals(Set.of("RESEARCHER"), authorities(u));
    }

    private static Set<String> authorities(User u) {
        return u.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
    }
}
