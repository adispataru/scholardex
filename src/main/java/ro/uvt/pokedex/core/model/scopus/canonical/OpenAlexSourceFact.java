package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H79 — an OpenAlex <b>source</b> (venue) source fact, derived offline from the works dumps
 * ({@code data/openalex/uvt_works.jsonl} + {@code uvt_citing_works.jsonl}). Each work embeds its
 * {@code primary_location.source} (id, issns, {@code is_oa}, {@code is_in_doaj}) plus a work-level
 * {@code apc_list} (the venue's advertised APC, USD-normalized via {@code value_usd}). Aggregating
 * over works keyed by source id yields a per-venue APC snapshot.
 *
 * <p>Purpose: the fee-journal exclusion the 2026 Informatică standard introduced. A gold-OA venue —
 * {@code isOa && apcUsd > 0} — conditions publication on a fee and is excluded from the threshold
 * points. This complements {@link ro.uvt.pokedex.core.model.doaj.DoajJournalFact}, which misses
 * gold-OA venues that aren't listed in DOAJ (e.g. MDPI <i>Electronics</i>: {@code is_oa=true},
 * {@code is_in_doaj=false}, {@code apc_usd=2165}). A <b>hybrid</b> journal that merely offers a paid
 * OA option carries an {@code apc_list} but {@code is_oa=false} and is NOT a fee journal.</p>
 *
 * <p>Offline-derived, so coverage is the UVT-authored + citing works we already downloaded, not the
 * full journal universe — sufficient for both the perspective-b (own pubs) and perspective-c (citing
 * pubs) exclusions, which only ever touch venues present in those works.</p>
 */
@Data
@Document(collection = "openalex.source_facts")
public class OpenAlexSourceFact {
    /** Stripped OpenAlex source id (no {@code https://openalex.org/} prefix), e.g. {@code S4210202905}. */
    @Id
    private String id;
    private String displayName;
    /** All ISSN tokens the venue carries (print + electronic) — the join key to canonical forums. */
    private List<String> issns = new ArrayList<>();
    /** Fully open-access venue (publication conditioned on the APC) — the gold-OA discriminator. */
    private Boolean isOa;
    /** Listed in DOAJ — secondary confirmation only (gold-OA venues can be absent from DOAJ). */
    private Boolean isInDoaj;
    /** Advertised APC in USD (max seen across works for this venue); null/0 when the venue lists none. */
    private Integer apcUsd;
    /** Number of works observed for this source in the dumps — provenance/diagnostics only. */
    private Integer worksObserved;

    private String source;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;

    /** The fee-journal predicate: gold-OA (is_oa) AND a positive advertised APC. */
    public boolean isFeeJournal() {
        return Boolean.TRUE.equals(isOa) && apcUsd != null && apcUsd > 0;
    }
}
