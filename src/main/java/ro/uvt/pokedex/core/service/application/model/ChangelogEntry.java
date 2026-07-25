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
        List<String> affects
) {

    public ChangelogEntry {
        affects = affects == null ? List.of() : List.copyOf(affects);
        audience = audience == null ? Audience.ALL : audience;
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
