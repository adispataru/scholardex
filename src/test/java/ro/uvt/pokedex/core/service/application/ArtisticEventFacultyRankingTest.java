package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H142 slice 7 — the events of a faculty's submitted CNFIS report, ranked at the level it reported. */
class ArtisticEventFacultyRankingTest {

    private final ArtisticEventRepository repository = mock(ArtisticEventRepository.class);
    private final ArtisticEventRankRegistrar registrar = mock(ArtisticEventRankRegistrar.class);
    private final ArtisticEventFacultyRanking ranking = new ArtisticEventFacultyRanking(repository, registrar);

    private static ArtisticEvent event(String name, RegistryStatus status, ArtisticEvent.Rank rank) {
        ArtisticEvent e = new ArtisticEvent();
        e.setId("id-" + name);
        e.setName(name);
        e.setStatus(status);
        e.setRank(rank);
        return e;
    }

    private static ActivityFileImportService.ReportedLevel reported(String event, String level) {
        return new ActivityFileImportService.ReportedLevel(event, level);
    }

    @Test
    void eachEventGetsTheLevelTheFacultyReportedUnlessTheRegistryAlreadyDecided() {
        ArtisticEvent enescu = event("Festivalul George Enescu", null, ArtisticEvent.Rank.INTERNATIONAL_TOP); // the CNFIS list
        ArtisticEvent rejected = event("Gala X", RegistryStatus.REJECTED, null);
        ArtisticEvent waiting = event("Festivalul Y", RegistryStatus.PROPOSED, null);
        when(repository.findAll()).thenReturn(List.of(enescu, rejected, waiting));

        ArtisticEventFacultyRanking.Outcome outcome = ranking.rank(List.of(
                reported("Festivalul Nou", "NATIONAL"), reported("Festivalul nou", "NATIONAL"),
                reported("Festivalul Disputat", "NATIONAL"), reported("Festivalul Disputat", "INTERNATIONAL"),
                reported("Festivalul George Enescu", "NATIONAL"),
                reported("Gala X", "INTERNATIONAL"),
                reported("Festivalul Y", "INTERNATIONAL_TOP")), "Muzică", "admin@uvt.ro", "Raportare CNFIS 2025 (FMT)");

        assertEquals(2, outcome.ranked());
        assertEquals(1, outcome.conflicting());
        assertEquals(1, outcome.alreadyRanked(), "the CNFIS list keeps its rank");
        assertEquals(1, outcome.leftAlone(), "a rejected name stays rejected");
        ArgumentCaptor<ArtisticEvent> saved = ArgumentCaptor.forClass(ArtisticEvent.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        Map<String, ArtisticEvent> byName = saved.getAllValues().stream()
                .collect(Collectors.toMap(ArtisticEvent::getName, Function.identity()));
        assertEquals(3, byName.size());

        ArtisticEvent nou = byName.get("Festivalul Nou");
        assertEquals(RegistryStatus.CONFIRMED, nou.getStatus());
        assertEquals(ArtisticEvent.Rank.NATIONAL, nou.getRank());
        assertEquals("FACULTY_CNFIS_REPORT", nou.getBasis());
        assertEquals("Muzică", nou.getDomainId());
        assertEquals("Raportare CNFIS 2025 (FMT): 2 × național", nou.getNote(), "two people reported it at the same level");
        assertEquals("admin@uvt.ro", nou.getDecidedBy());
        assertEquals("RANKED", nou.getHistory().getLast().getAction());

        assertEquals(ArtisticEvent.Rank.INTERNATIONAL_TOP, byName.get("Festivalul Y").getRank(), "a waiting proposal is ranked");
        assertEquals(RegistryStatus.CONFIRMED, byName.get("Festivalul Y").getStatus());

        ArtisticEvent disputed = byName.get("Festivalul Disputat");
        assertEquals(RegistryStatus.PROPOSED, disputed.getStatus(), "reported at two levels: the experts decide");
        assertEquals(null, disputed.getRank());
        assertTrue(disputed.getNote().contains("1 × internațional") && disputed.getNote().contains("1 × național"));
        assertEquals(List.of("Festivalul Disputat (" + disputed.getNote() + ")"), outcome.conflicts());
        verify(registrar).refresh();
    }

    @Test
    void aNewNameHoldingEveryDistinctiveWordOfARankedEventGoesToTheExperts() {
        ArtisticEvent enescu = event("Festivalul „George Enescu” (România)", null, ArtisticEvent.Rank.INTERNATIONAL_TOP);
        when(repository.findAll()).thenReturn(List.of(enescu));

        ArtisticEventFacultyRanking.Outcome outcome = ranking.rank(List.of(reported("Festivalul Internațional GEORGE ENESCU",
                "INTERNATIONAL_TOP"), reported("Festivalul Tinerelor Talente", "NATIONAL")), "Muzică", "admin@uvt.ro", "R");

        assertEquals(1, outcome.ranked(), "the young talents' festival is ranked");
        assertEquals(1, outcome.conflicting(), "Enescu's festival is a spelling for the experts to merge, not a second rank");
        ArgumentCaptor<ArtisticEvent> saved = ArgumentCaptor.forClass(ArtisticEvent.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        ArtisticEvent spelling = saved.getAllValues().stream().filter(e -> e.getName().contains("GEORGE")).findFirst().orElseThrow();
        assertEquals(RegistryStatus.PROPOSED, spelling.getStatus());
        assertTrue(spelling.getNote().endsWith("poate fi «Festivalul „George Enescu” (România)» din registru"), spelling.getNote());
        assertEquals(java.util.Set.of("george", "enescu"), ArtisticEventFacultyRanking.distinctive(enescu.getName()));
    }

    @Test
    void aReportUploadedInBatchesAddsUpAndADisagreementGoesToTheExperts() {
        List<ArtisticEvent> store = new java.util.ArrayList<>();
        ArtisticEvent byExperts = event("Festivalul Ales", RegistryStatus.CONFIRMED, ArtisticEvent.Rank.NATIONAL_TOP);
        byExperts.setBasis("TOP_FESTIVAL_ROMANIA");
        store.add(byExperts);
        when(repository.findAll()).thenAnswer(inv -> new java.util.ArrayList<>(store));
        when(repository.save(org.mockito.ArgumentMatchers.any(ArtisticEvent.class))).thenAnswer(inv -> {
            ArtisticEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId("new-" + store.size());
            store.removeIf(x -> x.getId().equals(e.getId()));
            store.add(e);
            return e;
        });
        String source = "Raportare CNFIS 2025 (FMT)";

        ArtisticEventFacultyRanking.Outcome first = ranking.rank(List.of(reported("Festivalul Meridian Nou", "INTERNATIONAL"),
                reported("Festivalul Constant", "NATIONAL")), "Muzică", "admin@uvt.ro", source);
        ArtisticEventFacultyRanking.Outcome second = ranking.rank(List.of(reported("Festivalul Meridian Nou", "NATIONAL"),
                reported("Festivalul Constant", "NATIONAL"), reported("Festivalul Ales", "INTERNATIONAL")), "Muzică", "admin@uvt.ro", source);
        ArtisticEventFacultyRanking.Outcome third = ranking.rank(List.of(reported("Festivalul Meridian Nou", "INTERNATIONAL")),
                "Muzică", "admin@uvt.ro", source);

        assertEquals(2, first.ranked());
        assertEquals(1, second.conflicting(), "the second batch reports Meridian at another level");
        assertEquals(2, second.alreadyRanked(), "Constant at the same level; the experts' festival untouched");
        assertEquals(1, third.conflicting(), "a later batch does not rank it back: the experts decide");
        Map<String, ArtisticEvent> byName = store.stream().collect(Collectors.toMap(ArtisticEvent::getName, Function.identity()));
        ArtisticEvent meridian = byName.get("Festivalul Meridian Nou");
        assertEquals(RegistryStatus.PROPOSED, meridian.getStatus());
        assertEquals(null, meridian.getRank());
        assertEquals(null, meridian.getBasis());
        assertEquals(source + ": 1 × internațional + 1 × național + 1 × internațional", meridian.getNote());
        assertEquals("INTERNATIONAL", meridian.getHistory().getLast().getFromLevel());
        ArtisticEvent constant = byName.get("Festivalul Constant");
        assertEquals(ArtisticEvent.Rank.NATIONAL, constant.getRank());
        assertEquals(source + ": 1 × național + 1 × național", constant.getNote());
        assertEquals(ArtisticEvent.Rank.NATIONAL_TOP, byName.get("Festivalul Ales").getRank());
        assertEquals("TOP_FESTIVAL_ROMANIA", byName.get("Festivalul Ales").getBasis());
    }

    @Test
    void nothingReportedChangesNothing() {
        assertEquals(ArtisticEventFacultyRanking.Outcome.NONE, ranking.rank(List.of(reported("  ", "NATIONAL"),
                reported("Festivalul Z", "LOCAL")), "Muzică", "admin@uvt.ro", "x"));
    }
}
