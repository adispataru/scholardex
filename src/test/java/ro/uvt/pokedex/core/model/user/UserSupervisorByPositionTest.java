package ro.uvt.pokedex.core.model.user;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.security.core.GrantedAuthority;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A head is a supervisor because of the appointment, not because of a stored role. These tests pin the
 * three things that makes safe: the right shows up everywhere the platform asks, it never reaches the
 * database, and an external candidate cannot obtain it.
 */
class UserSupervisorByPositionTest {

    private static User researcher() {
        User user = new User();
        user.setEmail("director@uvt.ro");
        user.setRoles(new HashSet<>(Set.of(UserRole.RESEARCHER)));
        return user;
    }

    private static List<String> authorities(User user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    @Test
    void aHeadHasTheAuthorityAndTheMenusAgree() {
        User head = researcher();
        head.setSupervisorByPosition(true);

        assertEquals(List.of("RESEARCHER", "SUPERVISOR"), authorities(head));
        assertTrue(head.hasRole("SUPERVISOR"));
        assertTrue(head.hasRole("RESEARCHER"));
        assertFalse(head.hasRole("PLATFORM_ADMIN"));
    }

    @Test
    void withoutAnAppointmentNothingChanges() {
        User user = researcher();

        assertEquals(List.of("RESEARCHER"), authorities(user));
        assertFalse(user.hasRole("SUPERVISOR"));
    }

    @Test
    void theStoredRolesAreNeverTouched() {
        User head = researcher();
        head.setSupervisorByPosition(true);
        head.getAuthorities();

        assertEquals(Set.of(UserRole.RESEARCHER), head.getRoles());
    }

    @Test
    void theRightIsNotWrittenToTheDatabase() {
        // The signed-in object is saved back by the profile pages; a persisted flag or role would outlive
        // the appointment.
        User head = researcher();
        head.setSupervisorByPosition(true);
        org.springframework.data.mongodb.core.convert.MongoCustomConversions conversions =
                new org.springframework.data.mongodb.core.convert.MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();

        Document stored = new Document();
        converter.write(head, stored);

        assertFalse(stored.containsKey("supervisorByPosition"), stored.keySet().toString());
        assertFalse(stored.containsKey("authority"), stored.keySet().toString());
        assertEquals(List.of("RESEARCHER"), stored.getList("roles", String.class));
    }

    @Test
    void aStoredSupervisorRoleKeepsWorkingAndIsNotDoubled() {
        User stored = researcher();
        stored.getRoles().add(UserRole.SUPERVISOR);
        stored.setSupervisorByPosition(true);

        assertEquals(List.of("RESEARCHER", "SUPERVISOR"), authorities(stored));
    }

    @Test
    void theAuthorityFollowsTheAppointmentWhenItChangesAfterTheFirstRead() {
        User user = researcher();
        assertEquals(List.of("RESEARCHER"), authorities(user)); // computed and cached

        user.setSupervisorByPosition(true);
        assertEquals(List.of("RESEARCHER", "SUPERVISOR"), authorities(user));

        user.setSupervisorByPosition(false);
        assertEquals(List.of("RESEARCHER"), authorities(user));
    }

    @Test
    void anExternalCandidateCannotBecomeASupervisor() {
        User external = researcher();
        external.setAccountKind(AccountKind.EXTERNAL);
        external.setSupervisorByPosition(true);

        assertEquals(List.of("RESEARCHER"), authorities(external));
        assertFalse(external.hasRole("SUPERVISOR"));
    }
}
