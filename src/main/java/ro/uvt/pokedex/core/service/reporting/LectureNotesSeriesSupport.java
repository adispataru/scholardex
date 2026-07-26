package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.Locale;

/**
 * The single definition of the "Lecture Notes …" book-series family.
 *
 * <p>This predicate answers ONE question — "is this venue a Lecture-Notes-family series?" — but two
 * scorers ask it for opposite reasons, and they must give the same answer or a paper is counted twice:
 *
 * <ul>
 *   <li>{@link ComputerScienceConferenceScoringService} uses it to ADMIT such a paper as a conference
 *       candidate (perspective b).</li>
 *   <li>{@link ComputerScienceBookService} uses it to EXCLUDE the same paper from book-chapter scoring
 *       (perspective d), precisely so it is not credited under both.</li>
 * </ul>
 *
 * <p>They were written as two private copies and drifted: the conference side matched
 * {@code "lecture notes in "} OR {@code "lecture notes on "}, the book side only {@code "lecture notes in "}.
 * Everything on a "Lecture Notes <b>on</b> …" series therefore passed both gates. Measured in production
 * on 2026-07-26: 22 {@code ch}/{@code bk} publications — 21 on "Lecture Notes on Data Engineering and
 * Communications Technologies", 1 on "Lecture Notes on Multidisciplinary Industrial Engineering" — were
 * scored as a conference AND as a book chapter, inflating perspective d and the Total. Five of them are
 * florin.fortis's (20 points), three alexandra.fortis's. Hence one predicate, in one place.
 *
 * <p>Case-insensitive because the source data is inconsistent about capitalisation ("in" vs "In"), and
 * the trailing space matters: it keeps "Lecture Notes in …" from matching a title that merely ends with
 * the word "in".
 *
 * <p><b>Known residual, deliberately not covered.</b> Springer's EAI series "Lecture Notes of the
 * Institute for Computer Sciences, Social-Informatics and Telecommunications Engineering, LNICST" is a
 * conference-proceedings series that neither "in " nor "on " matches. It holds 12 publications in
 * production and <b>zero</b> {@code ch}/{@code bk} ones, so it double-counts nothing today; widening the
 * family is a behaviour change beyond repairing the drift, and this predicate is also what denies genuine
 * monograph series ("Lecture Notes in Mathematics", "Lecture Notes in Physics") their book credit — so it
 * should be broadened deliberately, with that trade-off in view, not by reflex.
 */
public final class LectureNotesSeriesSupport {

    private LectureNotesSeriesSupport() {
    }

    /** Whether the forum is a "Lecture Notes in/on …" series. Null-safe; false for an unnamed forum. */
    public static boolean isLectureNotesSeries(ScholardexForumView forum) {
        String name = forum == null ? null : forum.getPublicationName();
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("lecture notes in ") || normalized.contains("lecture notes on ");
    }
}
