package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;
import ro.uvt.pokedex.core.service.security.ArtisticEventAccessService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H142 slice 3 — the experts rank a name once, for everyone; nobody decides on their own events. */
class ArtisticEventReviewServiceTest {

    private static final String MUSIC = "Muzică";
    private static final String THEATRE = "Teatru şi artele spectacolului";

    private final ArtisticEventRepository events = mock(ArtisticEventRepository.class);
    private final ArtisticEventDomainExpertsRepository domainExperts = mock(ArtisticEventDomainExpertsRepository.class);
    private final ActivityInstanceRepository records = mock(ActivityInstanceRepository.class);
    private final ArtisticEventAccessService access = mock(ArtisticEventAccessService.class);
    private final ArtisticEventRankRegistrar registrar = mock(ArtisticEventRankRegistrar.class);
    private final ArtisticEventReviewService service =
            new ArtisticEventReviewService(events, domainExperts, records, access, registrar);

    private final List<ArtisticEvent> registry = new ArrayList<>();
    private final List<ActivityInstance> saved = new ArrayList<>();

    private final Authentication expert = auth("expert@uvt.ro");
    private final Authentication performer = auth("ana@uvt.ro");

    @BeforeEach
    void setUp() {
        when(events.findAll()).thenAnswer(i -> new ArrayList<>(registry));
        when(events.findById(anyString())).thenAnswer(i -> registry.stream()
                .filter(e -> i.getArgument(0).equals(e.getId())).findFirst());
        when(events.save(any(ArtisticEvent.class))).thenAnswer(i -> {
            ArtisticEvent e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID().toString());
                registry.add(e);
            }
            return e;
        });
        when(records.findAllNamingAnEvent()).thenAnswer(i -> new ArrayList<>(saved));
        when(records.findAllByResearcherId(anyString())).thenAnswer(i -> saved.stream()
                .filter(r -> i.getArgument(0).equals(r.getResearcherId())).toList());
        when(domainExperts.findAll()).thenReturn(List.of());
        when(access.isPlatformAdmin(any())).thenReturn(false);
        when(access.domainsOf(any())).thenReturn(Set.of(MUSIC));
        when(access.canReview(eq(MUSIC), any())).thenReturn(true);
        when(access.canReview(eq(THEATRE), any())).thenReturn(false);
        when(access.domainsOfResearcher(anyString(), any())).thenReturn(Set.of(MUSIC));

        registry.add(event("Festivalul „George Enescu” (România)", ArtisticEvent.Rank.INTERNATIONAL_TOP, null));
    }

    private static Authentication auth(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("RESEARCHER")));
    }

    private static ArtisticEvent event(String name, ArtisticEvent.Rank rank, ArtisticEvent.Status status) {
        ArtisticEvent e = new ArtisticEvent();
        e.setId(UUID.randomUUID().toString());
        e.setName(name);
        e.setDomainId(MUSIC);
        e.setRank(rank);
        e.setStatus(status);
        return e;
    }

    private void record(String researcher, String event, String date, String suggestion, String evidence) {
        ActivityInstance r = new ActivityInstance();
        r.setId(UUID.randomUUID().toString());
        r.setResearcherId(researcher);
        r.setDate(date);
        r.setReferenceFields(Map.of(Activity.ReferenceField.EVENT_NAME, event));
        r.setFields(evidence == null ? Map.of() : Map.of("Dovezi", evidence));
        r.setEventLevelSuggestion(suggestion);
        saved.add(r);
    }

    private static ArtisticEventReviewService.RankForm form(ArtisticEvent.Rank rank) {
        return new ArtisticEventReviewService.RankForm(rank, ArtisticEvent.Kind.SEASON, "România",
                ArtisticEvent.Basis.TOP_INSTITUTION_ROMANIA.name(), "Stagiunea Filarmonicii", null);
    }

    @Test
    void theQueueGroupsTheRecordsThatNameAnUnknownEventAndLeavesTheKnownOnesOut() {
        record("ana@uvt.ro", "Filarmonica Banatul", "2023-10-23", "Fișa de verificare: CS 1.1", "https://filarmonica.ro/x");
        record("ion@uvt.ro", "FILARMONICA BANATUL", "2024-03-01", null, null);
        record("ion@uvt.ro", "Festivalul George Enescu (Romania)", "2023-09-10", null, null); // known, by its spelling

        List<ArtisticEventReviewService.QueueEntry> queue = service.page(expert, null).queue();

        assertEquals(1, queue.size());
        ArtisticEventReviewService.QueueEntry entry = queue.getFirst();
        assertEquals("filarmonica banatul", entry.key());
        assertEquals(2, entry.records());
        assertEquals(2, entry.researchers());
        assertEquals(List.of(2023, 2024), entry.years());
        assertEquals(List.of("Filarmonica Banatul", "FILARMONICA BANATUL"), entry.spellings());
        assertEquals(Map.of("Fișa de verificare: CS 1.1", 1), entry.suggestions());
        assertEquals(List.of("https://filarmonica.ro/x"), entry.evidence());
        assertEquals(Set.of(MUSIC), entry.domains());
        assertTrue(entry.canDecide());
    }

    @Test
    void anExpertRanksTheNameOnceForEveryoneAndTheRegistryReloads() {
        record("ana@uvt.ro", "Filarmonica Banatul", "2023-10-23", null, null);
        record("ion@uvt.ro", "FILARMONICA BANATUL", "2024-03-01", null, null);

        ArtisticEventReviewService.Outcome outcome = service.rank(List.of("filarmonica banatul"),
                form(ArtisticEvent.Rank.NATIONAL), expert);

        assertTrue(outcome.done(), outcome.messageKey());
        ArtisticEvent ranked = registry.stream().filter(e -> "Filarmonica Banatul".equals(e.getName())).findFirst().orElseThrow();
        assertEquals(ArtisticEvent.Status.CONFIRMED, ranked.getStatus());
        assertEquals(ArtisticEvent.Rank.NATIONAL, ranked.getRank());
        assertTrue(ranked.getAliases().isEmpty(), "spellings that differ in case or diacritics only are the same name");
        assertEquals(MUSIC, ranked.getDomainId());
        assertEquals("expert@uvt.ro", ranked.getDecidedBy());
        assertEquals("RANKED", ranked.getHistory().getFirst().getAction());
        verify(registrar).refresh();
        assertTrue(service.page(expert, null).queue().isEmpty(), "the name left the queue");
    }

    @Test
    void nobodyDecidesOnAnEventTheirOwnRecordsName() {
        record("ana@uvt.ro", "Filarmonica Banatul", "2023-10-23", null, null);

        ArtisticEventReviewService.QueueEntry entry = service.page(performer, null).queue().getFirst();
        assertFalse(entry.canDecide());
        assertEquals("artisticEvents.refused.own", entry.refusalKey());
        ArtisticEventReviewService.Outcome outcome = service.rank(List.of(entry.key()), form(ArtisticEvent.Rank.INTERNATIONAL), performer);
        assertFalse(outcome.done());
        assertEquals("artisticEvents.refused.own", outcome.messageKey());
        verify(registrar, never()).refresh();
    }

    @Test
    void aRankNeedsItsBasisAndAnExpertOfTheDomain() {
        record("ana@uvt.ro", "Filarmonica Banatul", "2023-10-23", null, null);
        assertEquals("artisticEvents.refused.rank", service.rank(List.of("filarmonica banatul"),
                new ArtisticEventReviewService.RankForm(ArtisticEvent.Rank.NATIONAL, null, null, null, null, null), expert).messageKey());
        assertEquals("artisticEvents.refused.domain", service.rank(List.of("filarmonica banatul"),
                new ArtisticEventReviewService.RankForm(ArtisticEvent.Rank.NATIONAL, null, null,
                        ArtisticEvent.Basis.CNFIS_UNION_PARTNERSHIP.name(), null, THEATRE), expert).messageKey());
    }

    @Test
    void aSpellingOfARankedEventIsMergedIntoIt() {
        record("ana@uvt.ro", "Festivalul Enescu", "2023-09-10", null, null);
        ArtisticEvent enescu = registry.getFirst();

        assertTrue(service.merge("festivalul enescu", enescu.getId(), expert).done());

        assertEquals(List.of("Festivalul Enescu"), enescu.getAliases());
        assertEquals("MERGED", enescu.getHistory().getFirst().getAction());
        assertTrue(service.page(expert, null).queue().isEmpty());
        verify(registrar).refresh();
    }

    @Test
    void severalSpellingsAreMergedAtOnceIntoAnEventBeyondTheFirstRowsOfTheTable() {
        // a registry larger than the table shows (50 rows): the merge list must still offer every ranked event
        for (int i = 0; i < 60; i++) {
            registry.add(event("Aaa Festival " + i, ArtisticEvent.Rank.NATIONAL, null));
        }
        ArtisticEvent enescu = registry.getFirst();
        record("ana@uvt.ro", "Festivalul Enescu", "2023-09-10", null, null);
        record("ion@uvt.ro", "Festivalul Internațional George Enescu", "2024-09-10", null, null);

        var page = service.page(expert, null);
        assertEquals(61, page.mergeTargets().size());
        assertTrue(page.mergeTargets().stream().anyMatch(t -> t.id().equals(enescu.getId())));

        assertEquals("artisticEvents.refused.target", service.mergeAll(List.of("festivalul enescu"), " ", expert).messageKey());
        assertEquals("artisticEvents.refused.none", service.mergeAll(List.of(), enescu.getId(), expert).messageKey());
        var outcome = service.mergeAll(List.of("festivalul enescu", "festivalul international george enescu"), enescu.getId(), expert);

        assertTrue(outcome.done());
        assertEquals("artisticEvents.done.mergedMany", outcome.messageKey());
        assertEquals(List.of("Festivalul Enescu", "Festivalul Internațional George Enescu"), enescu.getAliases());
        assertTrue(service.page(expert, null).queue().isEmpty());
    }

    @Test
    void aNameProposedFromAFileIsNoDecision() {
        ArtisticEvent proposal = event("Festivalul Meridian", null, ArtisticEvent.Status.PROPOSED);
        ArtisticEvent.Change proposed = new ArtisticEvent.Change();
        proposed.setAction("PROPOSED");
        proposed.setBy("admin@uvt.ro");
        proposed.setAt(java.time.Instant.parse("2026-10-03T08:00:00Z"));
        proposal.getHistory().add(proposed);
        registry.add(proposal);

        var page = service.page(expert, null);

        assertEquals(1, page.queue().size(), "it waits in the queue");
        assertTrue(page.decisions().isEmpty(), "but the decisions list only what experts decided");
    }

    @Test
    void aRejectedNameLeavesTheQueueWithItsReasonAndCanBeReopened() {
        record("ana@uvt.ro", "Concert", "2023-09-10", null, null);
        assertEquals("artisticEvents.refused.noteRequired", service.reject("concert", " ", expert).messageKey());

        assertTrue(service.reject("concert", "Not an event: name the festival or the institution.", expert).done());

        ArtisticEvent rejected = registry.stream().filter(e -> "Concert".equals(e.getName())).findFirst().orElseThrow();
        assertEquals(ArtisticEvent.Status.REJECTED, rejected.getStatus());
        assertNull(rejected.getRank());
        assertTrue(service.page(expert, null).queue().isEmpty());

        assertTrue(service.reopen(rejected.getId(), expert).done());
        assertEquals(ArtisticEvent.Status.PROPOSED, rejected.getStatus());
        assertEquals(1, service.page(expert, null).queue().size(), "back in the queue, with its record");
    }

    @Test
    void aStoredProposalIsRankedInPlaceAndShowsWhereItCameFrom() {
        ArtisticEvent proposal = event("Festivalul Timișoara Muzicală", null, ArtisticEvent.Status.PROPOSED);
        proposal.setSource("Anexa 6.1: FMT_Muzica.xlsx");
        registry.add(proposal);

        ArtisticEventReviewService.QueueEntry entry = service.page(expert, null).queue().getFirst();
        assertEquals("Anexa 6.1: FMT_Muzica.xlsx", entry.source());
        assertEquals(0, entry.records());

        assertTrue(service.rank(List.of(entry.key()), form(ArtisticEvent.Rank.NATIONAL_TOP), expert).done());
        assertEquals(ArtisticEvent.Status.CONFIRMED, proposal.getStatus());
        assertEquals(ArtisticEvent.Rank.NATIONAL_TOP, proposal.getRank());
        assertEquals(2, registry.size(), "ranked in place, not copied");
    }

    @Test
    void aRankedEventCanBeChangedWithItsHistoryButNotByAPerformerAtIt() {
        ArtisticEvent enescu = registry.getFirst();
        assertTrue(service.edit(enescu.getId(), form(ArtisticEvent.Rank.INTERNATIONAL), expert).done());
        ArtisticEvent.Change change = enescu.getHistory().getFirst();
        assertEquals(ArtisticEvent.Rank.INTERNATIONAL_TOP, change.getFromRank());
        assertEquals(ArtisticEvent.Rank.INTERNATIONAL, change.getToRank());
        assertEquals(ArtisticEvent.Status.CONFIRMED, enescu.getStatus());

        record("expert@uvt.ro", "Festivalul George Enescu (România)", "2024-09-01", null, null);
        assertEquals("artisticEvents.refused.own", service.edit(enescu.getId(), form(ArtisticEvent.Rank.INTERNATIONAL_TOP), expert).messageKey());
        verify(registrar, atLeastOnce()).refresh();
    }

    @Test
    void anotherDomainsProposalIsNotShownToAnExpertOfMusic() {
        when(access.domainsOfResearcher(eq("actor@uvt.ro"), any())).thenReturn(Set.of(THEATRE));
        record("actor@uvt.ro", "Festivalul de Teatru Scurt", "2023-05-01", null, null);
        assertTrue(service.page(expert, null).queue().isEmpty());
        when(access.isPlatformAdmin(any())).thenReturn(true);
        assertEquals(1, service.page(expert, null).queue().size(), "an admin sees every domain");
    }
}
