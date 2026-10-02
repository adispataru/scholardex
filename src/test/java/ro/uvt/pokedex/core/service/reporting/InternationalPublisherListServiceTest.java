package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H143 — the international lists and rankings that make a house one of international prestige. */
class InternationalPublisherListServiceTest {

    static SenseBookRanking sense(String name, SenseBookRanking.Rank rank) {
        SenseBookRanking ranking = new SenseBookRanking();
        ranking.setName(name);
        ranking.setRanking(rank);
        return ranking;
    }

    /** A few rows of the SENSE ranking, as the admin import stores them. */
    static SenseRankingRepository senseRankings() {
        SenseRankingRepository repository = mock(SenseRankingRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                sense("Brill", SenseBookRanking.Rank.B),
                sense("Frank Cass", SenseBookRanking.Rank.B),
                sense("Polity Press", SenseBookRanking.Rank.B),
                sense("Oxford University Press", SenseBookRanking.Rank.A),
                sense("Acco", SenseBookRanking.Rank.C),
                sense("Editorial Academica Espan", SenseBookRanking.Rank.E)));
        return repository;
    }

    private final InternationalPublisherListService lists = new InternationalPublisherListService(senseRankings());

    private String key(String publisher) {
        return lists.recognize(publisher).map(InternationalPublisherSupport.Recognition::key).orElse(null);
    }

    @Test
    void senseCountsItsRanksAAndBOnly() {
        assertEquals("SENSE", key("Brill Academic Publishers, Leiden"));
        assertEquals("SENSE", key("Frank Cass, London"));
        assertEquals("SENSE", key("Polity"), "a name inside the listed one, with a word of its own");
        assertEquals(Optional.empty(), lists.recognize("Acco"), "C does not count");
        assertEquals(Optional.empty(), lists.recognize("Editorial Academica Espan"), "E does not count");
        InternationalPublisherSupport.Recognition brill = lists.recognize("Brill").orElseThrow();
        assertEquals("Clasamentul SENSE al editurilor (categoriile A și B): Brill", brill.detail());
    }

    @Test
    void theUefiscdiListsHoldTheSocialSciencesAndTheArtsAndHumanities() {
        assertEquals("UEFISCDI_STIINTE_SOCIALE", key("L'Harmattan, Paris"));
        assertEquals("UEFISCDI_STIINTE_SOCIALE", key("Peter Lang"));
        assertEquals("UEFISCDI_ARTE_UMANISTE", key("Bärenreiter-Verlag Kassel"));
        assertEquals("UEFISCDI_ARTE_UMANISTE", key("Universal Edition"));
    }

    @Test
    void anEqualNameWinsOverAContainedOne() {
        assertEquals("Oxford University Press", lists.recognize("Oxford University Press").orElseThrow().listedName());
    }

    @Test
    void aRomanianHouseIsNeverLookedUpThere() {
        assertTrue(lists.isRomanian("Editura Peter Lang"), "written «Editura …»");
        assertEquals(Optional.empty(), lists.recognize("Editura Peter Lang"));
        assertTrue(lists.isRomanian("Economica, București"), "Editura Economică of the Sociology list, not the French one");
        assertEquals(Optional.empty(), lists.recognize("Economica"));
        assertEquals(Optional.empty(), lists.recognize("Paideia"), "the Romanian Paideia of CNCS");
        assertTrue(lists.isRomanian("Excelsior Art"), "a Romanian house of the WoS Master Book List");
        assertTrue(lists.isRomanian("Polirom, Iași"));
    }

    @Test
    void aRomanianNameOfGenericWordsOnlyCatchesNoForeignHouse() {
        // CNCS rates a Romanian "Editura University Press": it must not make Cambridge or Harvard Romanian
        assertTrue(lists.isRomanian("University Press"));
        assertFalse(lists.isRomanian("Cambridge University Press"));
        assertEquals("UEFISCDI_STIINTE_SOCIALE", key("Harvard University Press"));
    }

    @Test
    void lambertAcademicPublishingNeverCounts() {
        assertEquals(Optional.empty(), lists.recognize("Lambert Academic Publishing"));
        assertEquals(Optional.empty(), lists.recognize("LAP Lambert Academic Publishing, Saarbrücken"));
        assertFalse(lists.names().contains("Lambert Academic Publishing"));
        assertTrue(lists.names().contains("Brill"));
        assertTrue(lists.names().contains("Casa Ricordi"));
    }

    @Test
    void aShorterNameMatchesOnlyWhenTheListedOneAddsGenericWords() {
        assertEquals("UEFISCDI_STIINTE_SOCIALE", key("Harmattan"), "L'HARMATTAN");
        assertEquals("UEFISCDI_ARTE_UMANISTE", key("Rutgers University"), "Rutgers University Press");
        assertEquals(Optional.empty(), lists.recognize("Business Press Ltd."), "not Harvard Business School Press");
        assertEquals(Optional.empty(), lists.recognize("University of Arizona"), "not Arizona State University");
        assertEquals(Optional.empty(), lists.recognize("Collins"), "not Harper Collins");
    }

    @Test
    void genericWordsAloneMatchNothing() {
        assertEquals(Optional.empty(), lists.recognize("Academic Publishing"));
        assertEquals(Optional.empty(), lists.recognize("Editura Proprie"));
        assertEquals(Optional.empty(), lists.recognize("Cambridge Scholars Publishing"), "on none of the lists");
    }

    @Test
    void anUnreachableSenseCollectionLeavesTheOtherListsWorkingAndIsTriedAgainLater() {
        SenseRankingRepository down = mock(SenseRankingRepository.class);
        when(down.findAll()).thenThrow(new DataAccessResourceFailureException("down"));
        InternationalPublisherListService withoutSense = new InternationalPublisherListService(down);

        assertEquals(Optional.empty(), withoutSense.recognize("Frank Cass"), "on SENSE only");
        assertEquals("UEFISCDI_STIINTE_SOCIALE",
                withoutSense.recognize("Peter Lang").map(InternationalPublisherSupport.Recognition::key).orElse(null));
        withoutSense.recognize("Karthala");
        verify(down, times(1)).findAll(); // not once per book: again only after a while
    }

    @Test
    void theSupportReadsNothingUntilAServiceRegisters() {
        InternationalPublisherSupport.reset();
        assertEquals(Optional.empty(), InternationalPublisherSupport.recognize("Brill"));
        assertFalse(InternationalPublisherSupport.isRomanian("Editura Polirom"));
        InternationalPublisherSupport.register(lists);
        try {
            assertEquals("SENSE", InternationalPublisherSupport.recognize(" Brill ").orElseThrow().key());
        } finally {
            InternationalPublisherSupport.reset();
        }
    }
}
