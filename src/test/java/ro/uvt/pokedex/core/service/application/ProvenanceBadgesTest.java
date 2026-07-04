package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.ProvenanceBadge;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProvenanceBadgesTest {

    @Test
    void publicationBadgesFromSourceIdsAndOpenAccess() {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setEid("2-s2.0-123");
        pub.setWosId("WOS:000123");
        pub.setGoogleScholarId("gs-abc");
        pub.setOpenAccess(true);
        pub.setFreetoreadLabel("gold");

        List<ProvenanceBadge> badges = ProvenanceBadges.forPublication(pub);

        assertThat(badges).extracting(ProvenanceBadge::label)
                .containsExactly("Scopus", "WoS", "Google Scholar", "Open Access");
        // Web of Science is login-gated; the rest are public.
        assertThat(badge(badges, "WoS").requiresAuth()).isTrue();
        assertThat(badge(badges, "Scopus").requiresAuth()).isFalse();
        assertThat(badge(badges, "Open Access").tone()).isEqualTo("success");
        assertThat(badge(badges, "Open Access").tooltip()).contains("gold");
    }

    @Test
    void publicationWithNoSourceIdsHasNoBadges() {
        assertThat(ProvenanceBadges.forPublication(new ScholardexPublicationView())).isEmpty();
        assertThat(ProvenanceBadges.forPublication(null)).isEmpty();
    }

    @Test
    void forumBadgesFromMembershipSnapshot() {
        List<ProvenanceBadge> badges = ProvenanceBadges.forForum(
                Set.of("SCOPUS", "SCIE", "ESCI", "OPENALEX", "DBLP", "DOAJ", "ERIH"), true);

        assertThat(badges).extracting(ProvenanceBadge::label)
                .containsExactly("Scopus", "WoS", "OpenAlex", "DBLP", "DOAJ", "ERIH PLUS", "Open Access", "APC");
        // One consolidated WoS badge, gated, editions listed in the tooltip.
        ProvenanceBadge wos = badge(badges, "WoS");
        assertThat(wos.requiresAuth()).isTrue();
        assertThat(wos.tooltip()).contains("ESCI").contains("SCIE");
        assertThat(badge(badges, "APC").tone()).isEqualTo("warning");
    }

    @Test
    void forumWithoutApcOmitsApcBadge() {
        List<ProvenanceBadge> badges = ProvenanceBadges.forForum(Set.of("DOAJ"), false);
        assertThat(badges).extracting(ProvenanceBadge::label).containsExactly("DOAJ", "Open Access");
    }

    @Test
    void emptyMembershipHasNoBadges() {
        assertThat(ProvenanceBadges.forForum(Set.of(), false)).isEmpty();
        assertThat(ProvenanceBadges.forForum(null, false)).isEmpty();
    }

    private static ProvenanceBadge badge(List<ProvenanceBadge> badges, String label) {
        return badges.stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow();
    }
}
