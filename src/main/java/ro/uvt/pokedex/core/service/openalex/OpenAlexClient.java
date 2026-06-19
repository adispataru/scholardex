package ro.uvt.pokedex.core.service.openalex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * H66B Phase 4a — Java-direct OpenAlex client (keyless public REST API; no Python bridge). Fetches all of a
 * researcher's works by ORCID via cursor pagination. Polite-pool {@code mailto} is appended when configured.
 */
@Service
public class OpenAlexClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexClient.class);

    private final WebClient openAlexWebClient;
    private final String mailto;
    private final int perPage;
    private final int maxPages;

    public OpenAlexClient(
            @Qualifier("openAlexWebClient") WebClient openAlexWebClient,
            @Value("${openalex.api.mailto:}") String mailto,
            @Value("${openalex.api.per-page:200}") int perPage,
            @Value("${openalex.api.max-pages:200}") int maxPages) {
        this.openAlexWebClient = openAlexWebClient;
        this.mailto = mailto;
        this.perPage = Math.max(1, Math.min(200, perPage));
        this.maxPages = Math.max(1, maxPages);
    }

    /**
     * Fetches every OpenAlex work authored by the given ORCID, following the cursor until exhausted. The
     * ORCID must already be normalized (bare hyphenated form). Returns an empty list on a blank ORCID.
     */
    public List<OpenAlexWorksResponse.OpenAlexWork> fetchWorksByOrcid(String orcid) {
        List<OpenAlexWorksResponse.OpenAlexWork> works = new ArrayList<>();
        if (orcid == null || orcid.isBlank()) {
            return works;
        }
        String filter = "author.orcid:" + orcid;
        String cursor = "*";
        int page = 0;
        while (cursor != null && !cursor.isBlank() && page < maxPages) {
            final String currentCursor = cursor;
            OpenAlexWorksResponse response = openAlexWebClient.get()
                    .uri(builder -> {
                        builder.path("/works")
                                .queryParam("filter", filter)
                                .queryParam("per-page", perPage)
                                .queryParam("cursor", currentCursor);
                        if (mailto != null && !mailto.isBlank()) {
                            builder.queryParam("mailto", mailto);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .bodyToMono(OpenAlexWorksResponse.class)
                    .block();
            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                break;
            }
            works.addAll(response.getResults());
            cursor = response.getMeta() == null ? null : response.getMeta().getNext_cursor();
            page++;
        }
        log.info("OpenAlex fetch by ORCID {} completed: works={} pages={}", orcid, works.size(), page);
        return works;
    }

    /**
     * Batch-fetch works by OpenAlex id (≤100 per request via the {@code ids.openalex:W1|W2|…} OR-filter), returning
     * the id→work for each — used to resolve referenced/citing works' DOIs (H66B Phase 4a Stage 2). Ids must be the
     * bare {@code W…} form; the caller is responsible for caching the results.
     */
    public List<OpenAlexWorksResponse.OpenAlexWork> fetchWorksByIds(java.util.Collection<String> openAlexWorkIds) {
        List<OpenAlexWorksResponse.OpenAlexWork> works = new ArrayList<>();
        List<String> distinct = openAlexWorkIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        for (int from = 0; from < distinct.size(); from += 100) {
            List<String> batch = distinct.subList(from, Math.min(from + 100, distinct.size()));
            String filter = "ids.openalex:" + String.join("|", batch);
            OpenAlexWorksResponse response = openAlexWebClient.get()
                    .uri(builder -> {
                        builder.path("/works").queryParam("filter", filter).queryParam("per-page", 100);
                        if (mailto != null && !mailto.isBlank()) {
                            builder.queryParam("mailto", mailto);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .bodyToMono(OpenAlexWorksResponse.class)
                    .block();
            if (response != null && response.getResults() != null) {
                works.addAll(response.getResults());
            }
        }
        log.info("OpenAlex batch fetch by id: requested={} returned={}", distinct.size(), works.size());
        return works;
    }

    /**
     * Fetch the works that CITE any of the given works (incoming citations, H66B Phase 4a Ext A) via the
     * {@code cites:W1|W2|…} filter — cursor-paginated, cited ids batched (≤50/filter). Each returned work carries its
     * own {@code referenced_works}, so the caller can tell which of the cited ids it cites.
     */
    public List<OpenAlexWorksResponse.OpenAlexWork> fetchCitingWorks(java.util.Collection<String> citedWorkIds) {
        List<OpenAlexWorksResponse.OpenAlexWork> works = new ArrayList<>();
        List<String> distinct = citedWorkIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        for (int from = 0; from < distinct.size(); from += 50) {
            List<String> batch = distinct.subList(from, Math.min(from + 50, distinct.size()));
            String filter = "cites:" + String.join("|", batch);
            String cursor = "*";
            int page = 0;
            while (cursor != null && !cursor.isBlank() && page < maxPages) {
                final String currentCursor = cursor;
                OpenAlexWorksResponse response = openAlexWebClient.get()
                        .uri(builder -> {
                            builder.path("/works").queryParam("filter", filter)
                                    .queryParam("per-page", perPage).queryParam("cursor", currentCursor);
                            if (mailto != null && !mailto.isBlank()) {
                                builder.queryParam("mailto", mailto);
                            }
                            return builder.build();
                        })
                        .retrieve()
                        .bodyToMono(OpenAlexWorksResponse.class)
                        .block();
                if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                    break;
                }
                works.addAll(response.getResults());
                cursor = response.getMeta() == null ? null : response.getMeta().getNext_cursor();
                page++;
            }
        }
        log.info("OpenAlex fetch citing-works for {} cited ids: returned={}", distinct.size(), works.size());
        return works;
    }
}
