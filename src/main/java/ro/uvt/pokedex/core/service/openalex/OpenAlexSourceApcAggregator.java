package ro.uvt.pokedex.core.service.openalex;

import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceFact;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * H79/H80 — a stateful accumulator that derives per-venue APC facts from a stream of OpenAlex works.
 *
 * <p>Each work embeds its {@code primary_location.source} ({@code id}, {@code issn}, {@code is_oa},
 * {@code is_in_doaj}) plus a work-level {@code apc_list} (the venue's advertised APC, USD-normalized via
 * {@code value_usd}). {@link #observe(OpenAlexWorksResponse.OpenAlexWork)} folds one work into the running
 * per-source aggregate; {@link #toFacts} materializes one {@link OpenAlexSourceFact} per venue.</p>
 *
 * <p>Shared by the standalone {@link OpenAlexSourceApcImportService} (manual re-run) and the pipeline's
 * {@link OpenAlexBulkImportService} (folded into the works/citers stream so a rebuild produces the facts
 * in-DAG, before the stage-4 forum-membership projection reads them). Not thread-safe — one instance per
 * import run.</p>
 *
 * <p>Aggregation rule per source: {@code isOa} = OR across works (any work seeing it gold-OA marks it),
 * {@code apcUsd} = max advertised {@code apc_list.value_usd}. The fee predicate ({@code isOa && apcUsd > 0})
 * cleanly separates gold-OA venues (excluded) from hybrid journals (paid OA option but {@code is_oa=false}).</p>
 */
public final class OpenAlexSourceApcAggregator {

    private static final String OPENALEX_ID_PREFIX = "https://openalex.org/";

    private final Map<String, Aggregate> bySource = new LinkedHashMap<>();

    /** Fold one work's source + APC into the running aggregate. No-op for works without a resolvable source. */
    public void observe(OpenAlexWorksResponse.OpenAlexWork work) {
        if (work == null) {
            return;
        }
        OpenAlexWorksResponse.PrimaryLocation loc = work.getPrimary_location();
        OpenAlexWorksResponse.OpenAlexSource src = loc == null ? null : loc.getSource();
        if (src == null) {
            return;
        }
        String id = strip(src.getId());
        if (id == null || id.isBlank()) {
            return;
        }
        bySource.computeIfAbsent(id, Aggregate::new).observe(src, apcUsd(work.getApc_list()));
    }

    /** Distinct sources observed so far. */
    public int sourceCount() {
        return bySource.size();
    }

    /** Materialize one {@link OpenAlexSourceFact} per observed source. */
    public List<OpenAlexSourceFact> toFacts(String batchId, String correlationId, Instant now) {
        List<OpenAlexSourceFact> facts = new ArrayList<>(bySource.size());
        for (Aggregate agg : bySource.values()) {
            facts.add(agg.toFact(batchId, correlationId, now));
        }
        return facts;
    }

    private static Integer apcUsd(OpenAlexWorksResponse.Apc apc) {
        return apc == null ? null : apc.getValue_usd();
    }

    private static String strip(String id) {
        if (id == null) {
            return null;
        }
        return id.startsWith(OPENALEX_ID_PREFIX) ? id.substring(OPENALEX_ID_PREFIX.length()) : id;
    }

    /** Per-source running aggregate over the streamed works. */
    private static final class Aggregate {
        private final String id;
        private String displayName;
        private final LinkedHashSet<String> issns = new LinkedHashSet<>();
        private boolean isOa;
        private Boolean isInDoaj;
        private int apcUsd;
        private int works;

        Aggregate(String id) {
            this.id = id;
        }

        void observe(OpenAlexWorksResponse.OpenAlexSource src, Integer worksApcUsd) {
            works++;
            if (displayName == null && src.getDisplay_name() != null) {
                displayName = src.getDisplay_name();
            }
            if (src.getIssn() != null) {
                issns.addAll(src.getIssn());
            }
            if (Boolean.TRUE.equals(src.getIs_oa())) {
                isOa = true;
            }
            if (Boolean.TRUE.equals(src.getIs_in_doaj())) {
                isInDoaj = true;
            } else if (isInDoaj == null && src.getIs_in_doaj() != null) {
                isInDoaj = false;
            }
            if (worksApcUsd != null && worksApcUsd > apcUsd) {
                apcUsd = worksApcUsd;
            }
        }

        OpenAlexSourceFact toFact(String batchId, String correlationId, Instant now) {
            OpenAlexSourceFact fact = new OpenAlexSourceFact();
            fact.setId(id);
            fact.setDisplayName(displayName);
            fact.setIssns(new ArrayList<>(issns));
            fact.setIsOa(isOa);
            fact.setIsInDoaj(isInDoaj);
            fact.setApcUsd(apcUsd > 0 ? apcUsd : null);
            fact.setWorksObserved(works);
            fact.setSource("OPENALEX_WORKS_DUMP");
            fact.setSourceBatchId(batchId);
            fact.setSourceCorrelationId(correlationId);
            fact.setCreatedAt(now);
            fact.setUpdatedAt(now);
            return fact;
        }
    }
}
