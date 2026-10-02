package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H138 — the 13 competition domains of PN-IV PD/TE 2026 and their Domain documents. */
class UefiscdiCompetitionDomainsTest {

    @Test
    void theCatalogHasThe13DomainsOfAnexa1WithTheirFamiliesAndAuthorRules() {
        List<UefiscdiCompetitionDomains.CompetitionDomain> all = UefiscdiCompetitionDomains.all();
        assertEquals(13, all.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), all.stream().map(d -> d.code()).toList());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily.EXACT, UefiscdiCompetitionDomains.byCode(7).orElseThrow().family());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily.SOCIAL_ECONOMIC, UefiscdiCompetitionDomains.byCode(12).orElseThrow().family());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily.HUMANITIES, UefiscdiCompetitionDomains.byCode(13).orElseThrow().family());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.PrincipalAuthorRule.ALL_AUTHORS_6E, UefiscdiCompetitionDomains.byCode(1).orElseThrow().principalAuthorRule());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.PrincipalAuthorRule.ALL_AUTHORS_6E, UefiscdiCompetitionDomains.byCode(2).orElseThrow().principalAuthorRule());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.PrincipalAuthorRule.LAST_AUTHOR_TOO_6D, UefiscdiCompetitionDomains.byCode(9).orElseThrow().principalAuthorRule());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.PrincipalAuthorRule.FIRST_OR_CORRESPONDING, UefiscdiCompetitionDomains.byCode(3).orElseThrow().principalAuthorRule());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.PrincipalAuthorRule.ALL_AUTHORS, UefiscdiCompetitionDomains.byCode(11).orElseThrow().principalAuthorRule());
    }

    @Test
    void everyCategoryKeyNamesAQualifyingEditionAndTheExactDomainsCarryMultidisciplinarySciences() {
        for (UefiscdiCompetitionDomains.CompetitionDomain d : UefiscdiCompetitionDomains.all()) {
            assertTrue(d.wosCategories().size() >= 12, d.name() + " has only " + d.wosCategories().size() + " keys");
            for (String key : d.wosCategories()) {
                String edition = key.substring(key.lastIndexOf(" - ") + 3);
                assertTrue(Set.of("SCIE", "SSCI", "AHCI").contains(edition), key + ": ESCI never qualifies");
            }
            if (d.family() == ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily.EXACT) {
                assertTrue(d.wosCategories().contains("MULTIDISCIPLINARY SCIENCES - SCIE"), d.name());
            }
        }
        assertTrue(UefiscdiCompetitionDomains.byCode(1).orElseThrow().wosCategories().contains("MATHEMATICS - SCIE"));
        assertTrue(UefiscdiCompetitionDomains.byCode(2).orElseThrow().wosCategories().contains("COMPUTER SCIENCE, THEORY & METHODS - SCIE"));
        assertTrue(UefiscdiCompetitionDomains.byCode(13).orElseThrow().wosCategories().contains("HISTORY - AHCI"));
        assertEquals(UefiscdiCompetitionDomains.byCode(11).orElseThrow().wosCategories(),
                UefiscdiCompetitionDomains.byCode(12).orElseThrow().wosCategories(), "one shared SSCI set (decision 2026-10-02)");
    }

    @Test
    void theCatalogServiceWritesTheMissingDocumentsAndLeavesTheCommittedOnesAlone() {
        DomainRepository repository = mock(DomainRepository.class);
        UefiscdiCompetitionDomains.CompetitionDomain maths = UefiscdiCompetitionDomains.byCode(1).orElseThrow();
        Domain stored = new Domain();
        stored.setId(maths.domainId());
        stored.setName(maths.domainId());
        stored.setWosCategories(List.copyOf(maths.wosCategories()));
        stored.setDescription("PN-IV PD/TE 2026, Anexa 1, domeniul 1 (PE1); familia EXACT, autor principal ALL_AUTHORS_6E; categoriile Web of Science în lectura platformei (H138)");
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findById(maths.domainId())).thenReturn(Optional.of(stored));

        int written = new UefiscdiDomainCatalogService(repository).reconcile();

        assertEquals(13, written, "the committed one is untouched; the 'no domain' document is written too");
        ArgumentCaptor<Domain> saved = ArgumentCaptor.forClass(Domain.class);
        verify(repository, times(13)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(d -> d.getId().startsWith(UefiscdiCompetitionDomains.DOMAIN_ID_PREFIX)));
        assertTrue(saved.getAllValues().stream().noneMatch(d -> d.getId().equals(maths.domainId())));
    }
}
