package ro.uvt.pokedex.core.service.brainmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedProjectFactRepository;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDefinedProjectServiceTest {

    @Mock
    private UserDefinedProjectFactRepository repository;

    private UserDefinedProjectService service() {
        return new UserDefinedProjectService(repository);
    }

    private static UserDefinedProjectFact req(String euGrantId, String code, Long budget) {
        UserDefinedProjectFact f = new UserDefinedProjectFact();
        f.setEuGrantId(euGrantId);
        f.setCode(code);
        f.setBudget(budget);
        f.setTitle("T");
        return f;
    }

    @Test
    void idForPrefersEuGrantIdThenCodeHash() {
        assertThat(UserDefinedProjectService.idFor("101061610", "X")).isEqualTo("101061610");
        assertThat(UserDefinedProjectService.idFor(null, "PN-X")).startsWith("udp_");
        assertThat(UserDefinedProjectService.idFor("  ", "PN-X")).startsWith("udp_");
    }

    @Test
    void saveNewSetsLineageAndId() {
        when(repository.findById("101061610")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        UserDefinedProjectFact saved = service().save(req("101061610", null, 270000L), "admin@uvt.ro");

        assertThat(saved.getId()).isEqualTo("101061610");
        assertThat(saved.getBudget()).isEqualTo(270000L);
        assertThat(saved.getOrigin()).isEqualTo("MANUAL");
        assertThat(saved.getSource()).isEqualTo("USER_DEFINED");
        assertThat(saved.getSubmitterEmail()).isEqualTo("admin@uvt.ro");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void saveExistingPreservesCreatedAtAndSubmitter() {
        UserDefinedProjectFact existing = new UserDefinedProjectFact();
        existing.setId("101061610");
        existing.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        existing.setSubmitterEmail("first@uvt.ro");
        when(repository.findById("101061610")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        UserDefinedProjectFact saved = service().save(req("101061610", null, 500000L), "second@uvt.ro");

        assertThat(saved.getBudget()).isEqualTo(500000L);
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(saved.getSubmitterEmail()).isEqualTo("first@uvt.ro"); // not overwritten on update
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void deleteReturnsFalseWhenAbsent() {
        when(repository.existsById("nope")).thenReturn(false);
        assertThat(service().delete("nope")).isFalse();
    }
}
