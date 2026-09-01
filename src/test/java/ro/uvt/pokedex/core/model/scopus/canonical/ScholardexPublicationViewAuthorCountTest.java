package ro.uvt.pokedex.core.model.scopus.canonical;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H99: the scoring divisor {@code max(N-2, 1)} takes N from {@link ScholardexPublicationView#getAuthorCount()}.
 * {@code setAuthors} used to overwrite the bibliographic author_count (set by the row mappers from the
 * author_count column) with the resolved-id list size — so N tracked whichever scheduler task last rewrote the
 * canonical {@code authorIds} (the OpenAlex sync appended a split-identity duplicate, the Scopus canon rewrite
 * removed it), and a researcher's report totals flip-flopped between two exact values run to run. The column
 * value must win whenever a mapper set it; the id-list size is only a fallback for minimal mappers that don't
 * carry the column.
 */
class ScholardexPublicationViewAuthorCountTest {

    @Test
    void explicitAuthorCountSurvivesSetAuthors() {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setAuthorCount(4);
        // The inflated LOW state: 5 resolved ids (ghost duplicate) on a 4-author paper.
        view.setAuthors(List.of("sauth_a", "sauth_b", "sauth_c", "sauth_d", "sauth_ghost"));

        assertEquals(4, view.getAuthorCount());
        assertEquals(4, view.toScoringPublication().getAuthorCount());
    }

    @Test
    void explicitAuthorCountSurvivesAnIncompleteIdList() {
        // ~15k prod pubs resolve fewer canonical ids than the paper has authors; the divisor must not shrink.
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setAuthorCount(30);
        view.setAuthors(List.of("sauth_a", "sauth_b", "sauth_c"));

        assertEquals(30, view.getAuthorCount());
    }

    @Test
    void authorCountFallsBackToIdListSizeWhenNeverSet() {
        // Minimal mappers (workspace lists, citation drilldowns) don't read the author_count column.
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setAuthors(List.of("sauth_a", "sauth_b"));

        assertEquals(2, view.getAuthorCount());
    }
}
