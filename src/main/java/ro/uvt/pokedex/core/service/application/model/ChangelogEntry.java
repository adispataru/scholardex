package ro.uvt.pokedex.core.service.application.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * H86 — one dated platform change, read from the committed {@code changelog/changelog.json}. Entries ship in the
 * same commit as the change they document, so the log cannot drift from the code (an admin-editable table would).
 *
 * @param date        when the change reached users (release date, not commit date)
 * @param title       one line, in the reader's terms ("Conferințele ACM/EPTCS intră în categoria C")
 * @param body        a short paragraph: what changed and what it means for the reader
 * @param audience    who should see it — RESEARCHER, ADMIN, or ALL
 * @param scoringImpact true when the change can move someone's score; rendered distinctly because "why did my
 *                      score change?" is the question this page exists to answer
 * @param affects     free-form tags naming the touched reports/indicators ("FV Info 2026", "D(x)")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChangelogEntry(
        LocalDate date,
        String title,
        String body,
        Audience audience,
        boolean scoringImpact,
        Scope scope,
        List<String> reports,
        List<String> affects
) {

    public ChangelogEntry {
        affects = affects == null ? List.of() : List.copyOf(affects);
        reports = reports == null ? List.of() : List.copyOf(reports);
        audience = audience == null ? Audience.ALL : audience;
        // A REPORT-scoped entry that names no report is indistinguishable from a platform change to a reader,
        // so treat it as platform-wide rather than rendering an empty "affects these reports" claim.
        scope = (scope == null || (scope == Scope.REPORT && reports.isEmpty())) ? Scope.PLATFORM : scope;
    }

    /**
     * How far a change reaches. A researcher reading "my score moved" needs to know whether the rule changed
     * everywhere or only inside one fișă — the 2016 and 2026 standards deliberately diverge, so several
     * entries apply to exactly one of them.
     */
    public enum Scope {
        /** Applies across the platform (data sources, publications, sync, UI). */
        PLATFORM,
        /** Applies only to the report(s) listed in {@link #reports()}. */
        REPORT
    }

    public enum Audience {
        RESEARCHER,
        ADMIN,
        ALL;

        /** ADMIN entries are operational noise for a researcher; everything else is shared. */
        public boolean visibleTo(boolean viewerIsAdmin) {
            return this != ADMIN || viewerIsAdmin;
        }
    }
}
