package ro.uvt.pokedex.core.service.importing.scopus;

/**
 * H131 / H141 — the rule behind a canonical publication's citation counts. Each source has its own number
 * ({@code citedByCountScopus}, {@code citedByCountOpenAlex}); the scalar {@code citedByCount} that pages sort
 * by and the workspace totals is the best of the two. A source may hold several records of one work (Scopus
 * re-indexes a copy under a new EID — the copy starts at 0 while the old record keeps the history), so a
 * record that is not the one the publication already carries never lowers the source's number; a refresh of
 * that same record states the source's latest number as is.
 */
public final class CitationCountSupport {

    private CitationCountSupport() {
    }

    /** The source's number after a record of it arrives: the latest value for the same record, else the best. */
    public static Integer sourceCount(boolean otherRecordOfSameSource, Integer current, Integer incoming) {
        return otherRecordOfSameSource ? max(current, incoming) : (incoming != null ? incoming : current);
    }

    /** The scalar: the best of the two sources' numbers, {@code null} when neither is known. */
    public static Integer scalar(Integer scopus, Integer openAlex) {
        return max(scopus, openAlex);
    }

    /**
     * The scalar of an existing publication: the best of the two sources' numbers — and where one of them is
     * not known (a record from before H131, or a work only one source holds) the stored scalar stands in for
     * it, so a refresh never lowers a total it cannot account for.
     */
    public static Integer scalar(Integer scopus, Integer openAlex, Integer stored) {
        Integer best = max(scopus, openAlex);
        return scopus == null || openAlex == null ? max(best, stored) : best;
    }

    public static Integer max(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Integer.valueOf(Math.max(a, b));
    }
}
