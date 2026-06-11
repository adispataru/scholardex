package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnedCollectionRegistryTest {

    private final OwnedCollectionRegistry registry = new OwnedCollectionRegistry(
            Set.of("scopus.publication_facts", "scholardex.author_facts", "indicators"));

    @Test
    void isOwnedReflectsTheOwnedSet() {
        assertThat(registry.isOwned("scopus.publication_facts")).isTrue();
        assertThat(registry.isOwned("planuri.student")).isFalse();
    }

    @Test
    void assertWipeableAllowsOwnedAndRejectsForeign() {
        assertThatCode(() -> registry.assertWipeable("scholardex.author_facts")).doesNotThrowAnyException();

        assertThatThrownBy(() -> registry.assertWipeable("planuri.student"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not owned");
    }

    @Test
    void assertAllWipeableFailsIfAnyIsForeign() {
        assertThatThrownBy(() -> registry.assertAllWipeable(
                List.of("scopus.publication_facts", "skills")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skills");
    }
}
