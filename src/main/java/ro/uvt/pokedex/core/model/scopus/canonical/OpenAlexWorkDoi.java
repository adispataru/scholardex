package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H66B Phase 4a Stage 2 — durable cache of an OpenAlex work id → its DOI. Referenced/citing works arrive as bare
 * OpenAlex ids (no DOI in the work payload), so the citation builder fetches their DOIs once and caches them here so
 * they are not re-fetched across researchers or a full-rebuild replay. A row with {@code doi == null} records a
 * confirmed "fetched, no DOI" so it is not retried.
 */
@Data
@Document(collection = "openalex.work_doi")
public class OpenAlexWorkDoi {
    @Id
    private String id; // the OpenAlex work id (W…)
    private String doi; // normalized-or-raw DOI, or null when the work has none
    private Instant fetchedAt;
}
