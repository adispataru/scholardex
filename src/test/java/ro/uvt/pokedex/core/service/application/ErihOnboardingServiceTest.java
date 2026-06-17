package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.erih.ErihJournalFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErihOnboardingServiceTest {

    @Mock private ScholardexForumFactRepository forumRepository;
    @Mock private ErihJournalFactRepository erihJournalFactRepository;

    private static ScholardexForumFact forum(String id, String issn, String eIssn) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setIssn(issn);
        f.setEIssn(eIssn);
        return f;
    }

    private static ErihJournalFact erih(String id, String issn, String eIssn) {
        ErihJournalFact e = new ErihJournalFact();
        e.setId(id);
        e.setIssn(issn);
        e.setEIssn(eIssn);
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesErihIdOntoIssnMatchedForumsAndSkipsUnmatched() {
        ScholardexForumFact forumA = forum("sforum_a", "1234-5678", null);
        ScholardexForumFact forumB = forum("sforum_b", null, "2345-6789");
        when(forumRepository.findAll()).thenReturn(List.of(forumA, forumB));
        when(erihJournalFactRepository.findAll()).thenReturn(List.of(
                erih("E1", "12345678", null),     // matches forumA print ISSN
                erih("E2", "99999999", null),     // matches nothing
                erih("E3", null, "23456789")      // matches forumB eISSN
        ));

        ErihOnboardingService service = new ErihOnboardingService(forumRepository, erihJournalFactRepository);
        ImportProcessingResult result = service.onboardErih();

        assertEquals(3, result.getProcessedCount());
        assertEquals(2, result.getUpdatedCount());  // forumA + forumB
        assertEquals(1, result.getSkippedCount());  // E2 unmatched

        ArgumentCaptor<List<ScholardexForumFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(forumRepository).saveAll(captor.capture());
        Map<String, ScholardexForumFact> saved = captor.getValue().stream()
                .collect(Collectors.toMap(ScholardexForumFact::getId, f -> f));
        assertEquals(List.of("E1"), saved.get("sforum_a").getErihIds());
        assertEquals(List.of("E3"), saved.get("sforum_b").getErihIds());
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneErihJournalMatchingTwoForumsTagsBothWithSameErihId() {
        // The split-journal signal: both forums get E1, so dedup (C1 part 2) can later merge them.
        ScholardexForumFact forumA = forum("sforum_a", "1234-5678", null);
        ScholardexForumFact forumB = forum("sforum_b", null, "2345-6789");
        when(forumRepository.findAll()).thenReturn(List.of(forumA, forumB));
        when(erihJournalFactRepository.findAll()).thenReturn(List.of(
                erih("E1", "1234-5678", "2345-6789") // print matches A, eISSN matches B
        ));

        ErihOnboardingService service = new ErihOnboardingService(forumRepository, erihJournalFactRepository);
        service.onboardErih();

        ArgumentCaptor<List<ScholardexForumFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(forumRepository).saveAll(captor.capture());
        Map<String, ScholardexForumFact> saved = captor.getValue().stream()
                .collect(Collectors.toMap(ScholardexForumFact::getId, f -> f));
        assertEquals(List.of("E1"), saved.get("sforum_a").getErihIds());
        assertEquals(List.of("E1"), saved.get("sforum_b").getErihIds());
    }

    @Test
    void noMatchesSavesNothing() {
        ScholardexForumFact forumA = forum("sforum_a", "1234-5678", null);
        when(forumRepository.findAll()).thenReturn(List.of(forumA));
        when(erihJournalFactRepository.findAll()).thenReturn(List.of(erih("E9", "00000000", null)));

        ErihOnboardingService service = new ErihOnboardingService(forumRepository, erihJournalFactRepository);
        ImportProcessingResult result = service.onboardErih();

        assertEquals(1, result.getSkippedCount());
        assertTrue(result.getUpdatedCount() == 0);
        verify(forumRepository, never()).saveAll(anyList());
    }
}
