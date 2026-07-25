package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.application.model.ChangelogEntry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the REAL committed fixture: a malformed or mis-dated entry must fail here, not in production —
 * the changelog ships with the code it documents, so the fixture is part of the build.
 */
class ChangelogServiceTest {

    private ChangelogService service;

    @BeforeEach
    void setUp() {
        service = new ChangelogService();
        service.load();
    }

    @Test
    void committedFixtureLoadsAndIsNewestFirst() {
        List<ChangelogEntry> entries = service.entriesFor(true);

        assertThat(entries).isNotEmpty();
        assertThat(entries).isSortedAccordingTo((a, b) -> b.date().compareTo(a.date()));
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.title()).isNotBlank();
            assertThat(entry.body()).isNotBlank();
            assertThat(entry.date()).isNotNull();
        });
    }

    @Test
    void researchersDoNotSeeAdminOnlyEntries() {
        List<ChangelogEntry> researcherView = service.entriesFor(false);
        List<ChangelogEntry> adminView = service.entriesFor(true);

        assertThat(researcherView)
                .isNotEmpty()
                .noneMatch(entry -> entry.audience() == ChangelogEntry.Audience.ADMIN);
        assertThat(adminView).hasSizeGreaterThan(researcherView.size()); // fixture carries admin entries
        assertThat(adminView).anyMatch(entry -> entry.audience() == ChangelogEntry.Audience.ADMIN);
    }

    @Test
    void scoringImpactEntriesExistAndCarryAffectedAreas() {
        assertThat(service.entriesFor(false))
                .filteredOn(ChangelogEntry::scoringImpact)
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.affects()).isNotEmpty());
    }

    @Test
    void groupingKeepsNewestDayFirstAndPreservesEntriesPerDay() {
        var grouped = service.groupedByDate(true);

        assertThat(grouped).isNotEmpty();
        List<LocalDate> days = List.copyOf(grouped.keySet());
        assertThat(days).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(grouped.values().stream().mapToInt(List::size).sum())
                .isEqualTo(service.entriesFor(true).size());
    }

    @Test
    void newSinceCountsOnlyEntriesAfterTheLastVisitAndIgnoresFirstTimeReaders() {
        LocalDate newest = service.latestDate();

        assertThat(service.newSince(null, false)).isZero();
        assertThat(service.newSince(newest.atStartOfDay().toInstant(java.time.ZoneOffset.UTC), false)).isZero();
        assertThat(service.newSince(
                newest.minusYears(5).atStartOfDay().toInstant(java.time.ZoneOffset.UTC), false))
                .isEqualTo(service.entriesFor(false).size());
    }

    @Test
    void everyEntryDeclaresItsReachAndReportScopedOnesNameTheirFise() {
        List<ChangelogEntry> entries = service.entriesFor(true);

        assertThat(entries).allSatisfy(entry -> assertThat(entry.scope()).isNotNull());
        assertThat(entries)
                .filteredOn(e -> e.scope() == ChangelogEntry.Scope.REPORT)
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.reports()).isNotEmpty());
        assertThat(entries)
                .filteredOn(e -> e.scope() == ChangelogEntry.Scope.PLATFORM)
                .allSatisfy(e -> assertThat(e.reports()).isEmpty());
        // the two fișe are the only report labels in use — a typo here would render as a phantom report
        assertThat(entries.stream().flatMap(e -> e.reports().stream()).distinct())
                .allMatch(r -> r.equals("FV Info 2016") || r.equals("FV Info 2026"));
    }

    @Test
    void aReportScopedEntryWithoutReportsFallsBackToPlatformRatherThanClaimingNothing() {
        ChangelogEntry degenerate = new ChangelogEntry(LocalDate.of(2026, 7, 25), "t", "b",
                ChangelogEntry.Audience.ALL, false, ChangelogEntry.Scope.REPORT, List.of(), List.of());

        assertThat(degenerate.scope()).isEqualTo(ChangelogEntry.Scope.PLATFORM);
    }

    @Test
    void aMissingFixtureYieldsAnEmptyLogInsteadOfBreakingTheApp() {
        ChangelogService broken = new ChangelogService() {
            @Override
            public List<ChangelogEntry> entriesFor(boolean viewerIsAdmin) {
                return super.entriesFor(viewerIsAdmin);
            }
        };
        // simulate the load failure path by never loading (fixture absent → empty state is the default)
        assertThat(broken.entriesFor(true)).isEmpty();
        assertThat(broken.newSince(Instant.EPOCH, true)).isZero();
        assertThat(broken.latestDate()).isNull();
    }
}
