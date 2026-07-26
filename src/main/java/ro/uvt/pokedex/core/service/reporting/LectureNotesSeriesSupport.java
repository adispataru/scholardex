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
 * <p><b>Whether to broaden the family — assessed against production 2026-07-26, answer: no.</b>
 * The instinct is that a wider net is safer. Here it is the opposite, because of which way the points move:
 * a gated paper goes to the CONFERENCE scorer and takes the LNCS/Springer C floor (2 points), while an
 * ungated {@code ch} goes to the BOOK scorer and takes Springer's SENSE category B, halved for a chapter
 * (4 points). <b>Adding a series to this family moves its chapters 4 → 2.</b> Measured: broadening to the
 * usual Springer/IFIP conference families (LNICST, CCIS, AISC, IFIP Advances, Smart Innovation, Studies in
 * Computational Intelligence) would move <b>56</b> publications down, of which <b>0</b> belong to an
 * onboarded researcher. LNICST specifically holds 12 publications and zero {@code ch}/{@code bk}, so
 * adding it is a literal no-op. All cost, no benefit.
 *
 * <p>The name is also a poor discriminator in principle: "Lecture Notes in Physics", "Lecture Notes in
 * Educational Technology" and "Studies in Computational Intelligence" each publish BOTH conference
 * proceedings and monographs, so any series-level rule is necessarily wrong for one of the two. The real
 * discriminator is per-VOLUME — DBLP evidence naming the actual conference — which is the same lever
 * already needed for the ECML-PKDD workshop volumes (H90). Revisit this only when a researcher onboards
 * with a chapter on one of these series, and fix it per volume rather than by widening a string match.
 *
 * <p>The boundary is verified where it matters: across all 933 {@code ch}/{@code bk} publications in
 * production, conference-admitted and book-admitted partition cleanly — <b>0</b> are claimed by both. The
 * three book-scored chapters that do belong to onboarded researchers sit on genuine book series (Palgrave
 * Studies in Digital Business, SpringerBriefs, Studies in Big Data) and are correctly scored as books.
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
