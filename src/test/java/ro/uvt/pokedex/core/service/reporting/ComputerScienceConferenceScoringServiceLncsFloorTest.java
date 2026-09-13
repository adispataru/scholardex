package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * H106 S6 — the LNCS C floor is decided by the Crossref SERIES when known (wider "Lecture Notes in/on …"
 * family), by the forum name when not, and by the Springer DOI prefix only while the series is unknown.
 */
@ExtendWith(MockitoExtension.class)
class ComputerScienceConferenceScoringServiceLncsFloorTest {

    @Mock private ReportingLookupPort cacheService;
    @Mock private ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;

    private ComputerScienceConferenceScoringService service;

    @BeforeEach
    void setUp() {
        lenient().when(cacheService.maxAvailableYear()).thenReturn(2023);
        lenient().when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        service = new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);
    }

    @Test
    void knownNonLectureNotesSeriesScoresDDespiteTheSpringerDoi() {
        // Florin Fortiș's case: CCIS chapter citing the ontologies paper — was C/2 by DOI prefix, is D/1
        // on its (DBLP-restamped) proceedings forum.
        ScoringPublication publication = springerPaper("pub-ccis", "forum-amlta");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("AMLTA");
        forum.setAggregationTypes(List.of("Conference Proceeding"));
        when(cacheService.getForum("forum-amlta")).thenReturn(forum);
        when(dblpEvidenceRepository.findByPublicationId("pub-ccis"))
                .thenReturn(Optional.of(evidence("Communications in Computer and Information Science")));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertNull(score.getScoringInfo().get("lncsFloorEvidence"));
    }

    @Test
    void knownNonLectureNotesSeriesWithoutAForumEarnsNothingHere() {
        // No forum at all (a citing paper straight from Scopus/OpenAlex): the venue is unverifiable, so the
        // conference scorer gives 0 — the citation gate in ScientificProductionService prices it as D/1.
        ScoringPublication publication = springerPaper("pub-ccis-noforum", "forum-none");
        when(dblpEvidenceRepository.findByPublicationId("pub-ccis-noforum"))
                .thenReturn(Optional.of(evidence("Communications in Computer and Information Science")));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertNull(score.getScoringInfo().get("lncsFloorEvidence"));
    }

    @Test
    void lectureNotesFamilySeriesFloorsToCWithTheSeriesAsEvidence() {
        ScoringPublication publication = springerPaper("pub-lnns", "forum-acronym");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("3PGCIC");                 // DBLP-restamped: the name says nothing about the series
        forum.setAggregationTypes(List.of("Conference Proceeding"));
        when(cacheService.getForum("forum-acronym")).thenReturn(forum);
        when(dblpEvidenceRepository.findByPublicationId("pub-lnns"))
                .thenReturn(Optional.of(evidence("Lecture Notes in Networks and Systems")));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
        assertEquals("crossref-series: Lecture Notes in Networks and Systems", score.getScoringInfo().get("lncsFloorEvidence"));
    }

    @Test
    void unknownSeriesKeepsTheDoiPrefixFloorAndSaysSo() {
        ScoringPublication publication = springerPaper("pub-unknown", "forum-none");
        when(dblpEvidenceRepository.findByPublicationId("pub-unknown")).thenReturn(Optional.empty());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(2.0, score.getScore());
        assertEquals("doi-prefix (series unknown)", score.getScoringInfo().get("lncsFloorEvidence"));
    }

    @Test
    void lectureNotesOnSeriesForumNameFloorsToCUnderTheWiderFamily() {
        ScoringPublication publication = springerPaper("pub-lndect", "forum-lndect");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes on Data Engineering and Communications Technologies");
        forum.setAggregationType("Book Series");
        when(cacheService.getForum("forum-lndect")).thenReturn(forum);
        when(dblpEvidenceRepository.findByPublicationId("pub-lndect")).thenReturn(Optional.empty());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(2.0, score.getScore());
        assertEquals("forum-name: Lecture Notes on Data Engineering and Communications Technologies",
                score.getScoringInfo().get("lncsFloorEvidence"));
    }

    @Test
    void lnicstIsPartOfTheFamilyAndOtherSpringerSeriesAreNot() {
        assertEquals(true, LectureNotesSeriesSupport.isLectureNotesSeriesName(
                "Lecture Notes of the Institute for Computer Sciences, Social Informatics and Telecommunications Engineering"));
        assertEquals(true, LectureNotesSeriesSupport.isLectureNotesSeriesName("Lecture Notes in Networks and Systems"));
        assertEquals(true, LectureNotesSeriesSupport.isLectureNotesSeriesName("Lecture Notes on Data Engineering and Communications Technologies"));
        for (String other : List.of("Communications in Computer and Information Science", "Advances in Intelligent Systems and Computing",
                "Smart Innovation, Systems and Technologies", "IFIP Advances in Information and Communication Technology",
                "Studies in Computational Intelligence", "Springer Proceedings in Business and Economics", "Springer Theses")) {
            assertEquals(false, LectureNotesSeriesSupport.isLectureNotesSeriesName(other), other);
        }
    }

    private static ScoringPublication springerPaper(String id, String forumId) {
        return new ScoringPublication(id, null, forumId, "2023-01-01", "cp", "cp", List.of(), 0,
                "https://doi.org/10.1007/978-3-642-35326-0_26", null, "Web Service Based Approach for Viral Hepatitis Ontology Sharing", 0, Set.of());
    }

    private static ScholardexPublicationDblpEvidence evidence(String crossrefSeries) {
        ScholardexPublicationDblpEvidence ev = new ScholardexPublicationDblpEvidence();
        ev.setCrossrefSeries(crossrefSeries);
        return ev;
    }
}
