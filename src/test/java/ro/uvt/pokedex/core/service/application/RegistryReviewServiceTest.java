package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.registry.RegistryChange;
import ro.uvt.pokedex.core.model.registry.RegistryEntry;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.RegistryEntryRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;
import ro.uvt.pokedex.core.service.reporting.RegistryRegistrar;
import ro.uvt.pokedex.core.service.security.RegistryAccessService;

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

/** H142 slice 3, H144 — the experts rank a name once, for everyone, in every registry; nobody decides on their own names. */
class RegistryReviewServiceTest {

    private static final String MUSIC = "Muzică";
    private static final String THEATRE = "Teatru şi artele spectacolului";
    private static final RegistryKind ART = RegistryKind.ARTISTIC_EVENT;

    private final ArtisticEventRepository events = mock(ArtisticEventRepository.class);
    private final RegistryEntryRepository entries = mock(RegistryEntryRepository.class);
    private final RegistryDomainExpertsRepository domainExperts = mock(RegistryDomainExpertsRepository.class);
    private final ActivityInstanceRepository records = mock(ActivityInstanceRepository.class);
    private final RegistryAccessService access = mock(RegistryAccessService.class);
    private final ArtisticEventRankRegistrar artisticRegistrar = mock(ArtisticEventRankRegistrar.class);
    private final RegistryRegistrar registryRegistrar = mock(RegistryRegistrar.class);
    private final RegistryStores stores = new RegistryStores(events, entries, records, artisticRegistrar, registryRegistrar);
    private final RegistryReviewService service = new RegistryReviewService(stores, domainExperts, records, access);

    private final List<ArtisticEvent> registry = new ArrayList<>();
    private final List<RegistryEntry> generic = new ArrayList<>();
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
        when(entries.findAllByKind(any())).thenAnswer(i -> generic.stream().filter(e -> e.getKind() == i.getArgument(0)).toList());
        when(entries.findById(anyString())).thenAnswer(i -> generic.stream().filter(e -> i.getArgument(0).equals(e.getId())).findFirst());
        when(entries.save(any(RegistryEntry.class))).thenAnswer(i -> {
            RegistryEntry e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID().toString());
                generic.add(e);
            }
            return e;
        });
        when(records.findAllNamingAnEvent()).thenAnswer(i -> naming(Activity.ReferenceField.EVENT_NAME));
        when(records.findAllNamingAConference()).thenAnswer(i -> naming(Activity.ReferenceField.CONFERENCE_NAME));
        when(records.findAllNamingAnOrganization()).thenAnswer(i -> naming(Activity.ReferenceField.ORGANIZATION_NAME));
        when(records.findAllNamingAnAward()).thenAnswer(i -> naming(Activity.ReferenceField.AWARD_NAME));
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

    private List<ActivityInstance> naming(Activity.ReferenceField field) {
        return saved.stream().filter(r -> r.getReferenceFields() != null && r.getReferenceFields().containsKey(field)).toList();
    }

    private static Authentication auth(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("RESEARCHER")));
    }

    private static ArtisticEvent event(String name, ArtisticEvent.Rank rank, RegistryStatus status) {
        ArtisticEvent e = new ArtisticEvent();
        e.setId(UUID.randomUUID().toString());
        e.setName(name);
        e.setDomainId(MUSIC);
        e.setRank(rank);
        e.setStatus(status);
        return e;
    }

    private void record(String researcher, String event, String date, String suggestion, String evidence) {
        record(researcher, Activity.ReferenceField.EVENT_NAME, event, date, suggestion, evidence);
    }

    private void record(String researcher, Activity.ReferenceField field, String name, String date, String suggestion, String evidence) {
        ActivityInstance r = new ActivityInstance();
        r.setId(UUID.randomUUID().toString());
        r.setResearcherId(researcher);
        r.setDate(date);
        r.setReferenceFields(Map.of(field, name));
        r.setFields(evidence == null ? Map.of() : Map.of("Dovezi", evidence));
        r.setEventLevelSuggestion(suggestion);
        saved.add(r);
    }

    private static RegistryReviewService.RankForm form(ArtisticEvent.Rank rank) {
        return new RegistryReviewService.RankForm(rank.name(), ArtisticEvent.Kind.SEASON.name(), List.of(), "România",
                ArtisticEvent.Basis.TOP_INSTITUTION_ROMANIA.name(), "Stagiunea Filarmonicii", null);
    }

    private static RegistryReviewService.RankForm conference(String level, List<String> criteria) {
        return new RegistryReviewService.RankForm(level, "CONFERENCE", criteria, null, "COMISIA_28_CRITERIA", null, null);
    }

    // ── artistic events (H142 slice 3), through the engine ─────────────────────

    @Test
    void theQueueGroupsTheRecordsThatNameAnUnknownEventAndLeavesTheKnownOnesOut() {
        record("ana@uvt.ro", "Filarmonica Banatul", "2023-10-23", "Fișa de verificare: CS 1.1", "https://filarmonica.ro/x");
        record("ion@uvt.ro", "FILARMONICA BANATUL", "2024-03-01", null, null);
        record("ion@uvt.ro", "Festivalul George Enescu (Romania)", "2023-09-10", null, null); // known, by its spelling

        List<RegistryReviewService.QueueEntry> queue = service.page(ART, expert, null).queue();

        assertEquals(1, queue.size());
        RegistryReviewService.QueueEntry entry = queue.getFirst();
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

        RegistryReviewService.Outcome outcome = service.rank(ART, List.of("filarmonica banatul"),
                form(ArtisticEvent.Rank.NATIONAL), expert);

        assertTrue(outcome.done(), outcome.messageKey());
        ArtisticEvent ranked = registry.stream().filter(e -> "Filarmonica Banatul".equals(e.getName())).findFirst().orElseThrow();
        assertEquals(RegistryStatus.CONFIRMED, ranked.getStatus());
        assertEquals(ArtisticEvent.Rank.NATIONAL, ranked.getRank());
        assertEquals(ArtisticEvent.Kind.SEASON, ranked.getKind());
        assertTrue(ranked.getAliases().isEmpty(), "spellings that differ in case or diacritics only are the same name");
        assertEquals(MUSIC, ranked.getDomainId());
        assertEquals("expert@uvt.ro", ranked.getDecidedBy());
        assertEquals("RANKED", ranked.getHistory().getFirst().getAction());
        verify(artisticRegistrar).refresh();
        verify(registryRegistrar, never()).refresh();
        assertTrue(service.page(ART, expert, null).queue().isEmpty(), "the name left the queue");
    }

    @Test
    void nobodyDecidesOnANameTheirOwnRecordsGive() {
        record("ana@uvt.ro", "Filarmonica Banatul", "2023-10-23", null, null);

        RegistryReviewService.QueueEntry entry = service.page(ART, performer, null).queue().getFirst();
        assertFalse(entry.canDecide());
        assertEquals("registry.refused.own", entry.refusalKey());
        RegistryReviewService.Outcome outcome = service.rank(ART, List.of(entry.key()), form(ArtisticEvent.Rank.INTERNATIONAL), performer);
        assertFalse(outcome.done());
        assertEquals("registry.refused.own", outcome.messageKey());
        verify(artisticRegistrar, never()).refresh();
    }

    @Test
    void aRankNeedsALevelAndABasisOfTheRegistryAndAnExpertOfTheDomain() {
        record("ana@uvt.ro", "Filarmonica Banatul", "2023-10-23", null, null);
        assertEquals("registry.refused.rank", service.rank(ART, List.of("filarmonica banatul"),
                new RegistryReviewService.RankForm("NATIONAL", null, List.of(), null, null, null, null), expert).messageKey());
        assertEquals("registry.refused.rank", service.rank(ART, List.of("filarmonica banatul"),
                new RegistryReviewService.RankForm("NATIONAL", null, List.of(), null, "COMISIA_28_CRITERIA", null, null), expert).messageKey(),
                "a basis of another registry");
        assertEquals("registry.refused.domain", service.rank(ART, List.of("filarmonica banatul"),
                new RegistryReviewService.RankForm("NATIONAL", null, List.of(), null,
                        ArtisticEvent.Basis.CNFIS_UNION_PARTNERSHIP.name(), null, THEATRE), expert).messageKey());
    }

    @Test
    void aSpellingOfARankedEventIsMergedIntoIt() {
        record("ana@uvt.ro", "Festivalul Enescu", "2023-09-10", null, null);
        ArtisticEvent enescu = registry.getFirst();

        assertTrue(service.merge(ART, "festivalul enescu", enescu.getId(), expert).done());

        assertEquals(List.of("Festivalul Enescu"), enescu.getAliases());
        assertEquals("MERGED", enescu.getHistory().getFirst().getAction());
        assertTrue(service.page(ART, expert, null).queue().isEmpty());
        verify(artisticRegistrar).refresh();
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

        var page = service.page(ART, expert, null);
        assertEquals(61, page.mergeTargets().size());
        assertTrue(page.mergeTargets().stream().anyMatch(t -> t.id().equals(enescu.getId())));

        assertEquals("registry.refused.target", service.mergeAll(ART, List.of("festivalul enescu"), " ", expert).messageKey());
        assertEquals("registry.refused.none", service.mergeAll(ART, List.of(), enescu.getId(), expert).messageKey());
        var outcome = service.mergeAll(ART, List.of("festivalul enescu", "festivalul international george enescu"), enescu.getId(), expert);

        assertTrue(outcome.done());
        assertEquals("registry.done.mergedMany", outcome.messageKey());
        assertEquals(List.of("Festivalul Enescu", "Festivalul Internațional George Enescu"), enescu.getAliases());
        assertTrue(service.page(ART, expert, null).queue().isEmpty());
    }

    @Test
    void aNameProposedFromAFileIsNoDecision() {
        ArtisticEvent proposal = event("Festivalul Meridian", null, RegistryStatus.PROPOSED);
        proposal.getHistory().add(RegistryReviewService.change(java.time.Instant.parse("2026-10-03T08:00:00Z"),
                "admin@uvt.ro", "PROPOSED", null, null, null));
        registry.add(proposal);

        var page = service.page(ART, expert, null);

        assertEquals(1, page.queue().size(), "it waits in the queue");
        assertTrue(page.decisions().isEmpty(), "but the decisions list only what experts decided");
    }

    @Test
    void aRejectedNameLeavesTheQueueWithItsReasonAndCanBeReopened() {
        record("ana@uvt.ro", "Concert", "2023-09-10", null, null);
        assertEquals("registry.refused.noteRequired", service.reject(ART, "concert", " ", expert).messageKey());

        assertTrue(service.reject(ART, "concert", "Not an event: name the festival or the institution.", expert).done());

        ArtisticEvent rejected = registry.stream().filter(e -> "Concert".equals(e.getName())).findFirst().orElseThrow();
        assertEquals(RegistryStatus.REJECTED, rejected.getStatus());
        assertNull(rejected.getRank());
        assertTrue(service.page(ART, expert, null).queue().isEmpty());

        assertTrue(service.reopen(ART, rejected.getId(), expert).done());
        assertEquals(RegistryStatus.PROPOSED, rejected.getStatus());
        assertEquals(1, service.page(ART, expert, null).queue().size(), "back in the queue, with its record");
    }

    @Test
    void aStoredProposalIsRankedInPlaceAndShowsWhereItCameFrom() {
        ArtisticEvent proposal = event("Festivalul Timișoara Muzicală", null, RegistryStatus.PROPOSED);
        proposal.setSource("Anexa 6.1: FMT_Muzica.xlsx");
        registry.add(proposal);

        RegistryReviewService.QueueEntry entry = service.page(ART, expert, null).queue().getFirst();
        assertEquals("Anexa 6.1: FMT_Muzica.xlsx", entry.source());
        assertEquals(0, entry.records());

        assertTrue(service.rank(ART, List.of(entry.key()), form(ArtisticEvent.Rank.NATIONAL_TOP), expert).done());
        assertEquals(RegistryStatus.CONFIRMED, proposal.getStatus());
        assertEquals(ArtisticEvent.Rank.NATIONAL_TOP, proposal.getRank());
        assertEquals(2, registry.size(), "ranked in place, not copied");
    }

    @Test
    void aRankedEventCanBeChangedWithItsHistoryButNotByAPerformerAtIt() {
        ArtisticEvent enescu = registry.getFirst();
        assertTrue(service.edit(ART, enescu.getId(), form(ArtisticEvent.Rank.INTERNATIONAL), expert).done());
        RegistryChange change = enescu.getHistory().getFirst();
        assertEquals("INTERNATIONAL_TOP", change.getFromLevel());
        assertEquals("INTERNATIONAL", change.getToLevel());
        assertEquals(RegistryStatus.CONFIRMED, enescu.getStatus());

        record("expert@uvt.ro", "Festivalul George Enescu (România)", "2024-09-01", null, null);
        assertEquals("registry.refused.own", service.edit(ART, enescu.getId(), form(ArtisticEvent.Rank.INTERNATIONAL_TOP), expert).messageKey());
        verify(artisticRegistrar, atLeastOnce()).refresh();
    }

    @Test
    void anotherDomainsProposalIsNotShownToAnExpertOfMusic() {
        when(access.domainsOfResearcher(eq("actor@uvt.ro"), any())).thenReturn(Set.of(THEATRE));
        record("actor@uvt.ro", "Festivalul de Teatru Scurt", "2023-05-01", null, null);
        assertTrue(service.page(ART, expert, null).queue().isEmpty());
        when(access.isPlatformAdmin(any())).thenReturn(true);
        assertEquals(1, service.page(ART, expert, null).queue().size(), "an admin sees every domain");
    }

    // ── conferences, organisations, awards (H144) ─────────────────────────────

    @Test
    void aConferenceIsInternationalExactlyWhenTwoOfComisia28sCriteriaHold() {
        record("ana@uvt.ro", Activity.ReferenceField.CONFERENCE_NAME, "ECER 2024", "2024-08-27", null, null);
        RegistryKind conf = RegistryKind.SCIENTIFIC_EVENT;

        assertEquals("registry.refused.criteria", service.rank(conf, List.of("ecer 2024"),
                conference("INTERNATIONAL", List.of("INTERNATIONAL_ORGANISER")), expert).messageKey());
        assertEquals("registry.refused.criteria", service.rank(conf, List.of("ecer 2024"),
                conference("NATIONAL", List.of("INTERNATIONAL_ORGANISER", "SESSIONS_LANGUAGE")), expert).messageKey());
        assertEquals("registry.refused.rank", service.rank(conf, List.of("ecer 2024"),
                conference("INTERNATIONAL_TOP", List.of()), expert).messageKey(), "a level of another registry");

        assertTrue(service.rank(conf, List.of("ecer 2024"),
                conference("INTERNATIONAL", List.of("INTERNATIONAL_ORGANISER", "PROCEEDINGS_LANGUAGE")), expert).done());
        RegistryEntry ranked = generic.getFirst();
        assertEquals(RegistryKind.SCIENTIFIC_EVENT, ranked.getKind());
        assertEquals("INTERNATIONAL", ranked.getLevel());
        assertEquals(List.of("INTERNATIONAL_ORGANISER", "PROCEEDINGS_LANGUAGE"), ranked.getCriteria());
        assertEquals("COMISIA_28_CRITERIA", ranked.getBasis());
        verify(registryRegistrar).refresh();
        verify(artisticRegistrar, never()).refresh();
        assertTrue(service.page(conf, expert, null).queue().isEmpty());
    }

    @Test
    void anAwardNeedsItsNatureAndTheTabsCountWhatWaitsInEachRegistry() {
        record("ion@uvt.ro", Activity.ReferenceField.AWARD_NAME, "Premiul Academiei Române", "2023-01-01", null, null);
        record("ion@uvt.ro", Activity.ReferenceField.ORGANIZATION_NAME, "EERA", "2022-01-01", null, null);
        record("ion@uvt.ro", Activity.ReferenceField.ORGANIZATION_NAME, "UCMR", "2022-01-01", null, null);

        var page = service.page(RegistryKind.AWARD, expert, null);
        assertEquals(Map.of(ART, 0, RegistryKind.SCIENTIFIC_EVENT, 0, RegistryKind.ORGANIZATION, 2, RegistryKind.AWARD, 1),
                page.tabs().stream().collect(java.util.stream.Collectors.toMap(RegistryReviewService.KindTab::kind,
                        RegistryReviewService.KindTab::waiting)));

        assertEquals("registry.refused.category", service.rank(RegistryKind.AWARD, List.of("premiul academiei romane"),
                new RegistryReviewService.RankForm("NATIONAL", null, List.of(), "România", "AWARDING_BODY", null, null), expert).messageKey());
        assertTrue(service.rank(RegistryKind.AWARD, List.of("premiul academiei romane"),
                new RegistryReviewService.RankForm("NATIONAL", "SCIENTIFIC", List.of(), "România", "AWARDING_BODY", null, null), expert).done());
        assertEquals("SCIENTIFIC", generic.getFirst().getCategory());
    }

    @Test
    void aSharedEntryIsEveryExpertsToMergeIntoAndOnlyAnAdminsToChange() {
        RegistryEntry unesco = RegistryEntry.of(RegistryKind.ORGANIZATION);
        unesco.setId("unesco");
        unesco.setName("UNESCO");
        unesco.setStatus(RegistryStatus.CONFIRMED);
        unesco.setLevel("INTERNATIONAL");
        unesco.setBasis("SCOPE");
        generic.add(unesco);
        record("ion@uvt.ro", Activity.ReferenceField.ORGANIZATION_NAME, "UNESCO Paris", "2023-01-01", null, null);

        var page = service.page(RegistryKind.ORGANIZATION, expert, null);
        assertEquals(1, page.items().size(), "a body of no domain is listed for every expert");
        assertFalse(page.items().getFirst().canEdit(), "but changed by an admin only");
        assertEquals(1, page.mergeTargets().size());

        assertTrue(service.merge(RegistryKind.ORGANIZATION, "unesco paris", "unesco", expert).done());
        assertEquals(List.of("UNESCO Paris"), unesco.getAliases());
        assertEquals("registry.refused.domain", service.edit(RegistryKind.ORGANIZATION, "unesco",
                new RegistryReviewService.RankForm("NATIONAL", null, List.of(), null, "SCOPE", null, null), expert).messageKey());
    }

    @Test
    void anAdminRanksANameOfNoDomainAsASharedEntry() {
        when(access.domainsOfResearcher(eq("nou@uvt.ro"), any())).thenReturn(Set.of());
        record("nou@uvt.ro", Activity.ReferenceField.ORGANIZATION_NAME, "Asociația Psihologilor din România", "2024-03-01", null, null);
        RegistryReviewService.RankForm national = new RegistryReviewService.RankForm("NATIONAL", "ASSOCIATION", List.of(),
                "România", "SCOPE", null, null);
        assertTrue(service.page(RegistryKind.ORGANIZATION, expert, null).queue().isEmpty(), "a department mapped to no domain");
        assertEquals("registry.refused.domain", service.rank(RegistryKind.ORGANIZATION,
                List.of("asociatia psihologilor din romania"), national, expert).messageKey());

        when(access.isPlatformAdmin(any())).thenReturn(true);
        assertTrue(service.rank(RegistryKind.ORGANIZATION, List.of("asociatia psihologilor din romania"), national, expert).done());
        assertNull(generic.getFirst().getDomainId(), "shared, like the bodies of the initial list");
        assertEquals("NATIONAL", generic.getFirst().getLevel());
    }
}
