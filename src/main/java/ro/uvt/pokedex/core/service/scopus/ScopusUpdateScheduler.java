package ro.uvt.pokedex.core.service.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.importing.ScopusDataService;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksRequest;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksResponse;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidRequest;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidResponse;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScopusUpdateScheduler {

    private final ScopusPublicationUpdateRepository taskRepo;
    private final ScopusPublicationRepository publicationRepo;
    private final ScopusDataService scopusDataService;
    private final ScopusCitationUpdateRepository citationsTaskRepo;
    private final ScopusCitationRepository citationRepo;

    private final WebClient scopusPythonClient;
    private final ObjectMapper mapper = new ObjectMapper();


    @Value("${scopus.update.page-size:100}")
    private int pageSize;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId Z = ZoneId.systemDefault();



    @Scheduled(fixedDelayString = "${scopus.update.poll-ms:60000}")
    public void pollQueue() {
        String batchTaskId = "batch-" + UUID.randomUUID();
        AutoCloseable batchContext = SchedulerCorrelationSupport.withSchedulerContext(
                "scopus.update.poll",
                batchTaskId,
                "start"
        );
        long startedAt = System.currentTimeMillis();
        int publicationTasks = 0;
        int citationTasks = 0;
        log.info("Scopus scheduler poll started: batchTaskId={}", batchTaskId);
        try {
            publicationTasks = processPublicationTasks();
            citationTasks = processCitationTasks();
            MDC.put("phase", "complete");
            log.info("Scopus scheduler poll completed: batchTaskId={}, publicationTasks={}, citationTasks={}, durationMs={}",
                    batchTaskId, publicationTasks, citationTasks, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            MDC.put("phase", "failed");
            log.error("Scopus scheduler poll failed: batchTaskId={}, durationMs={}",
                    batchTaskId, System.currentTimeMillis() - startedAt, e);
            throw e;
        } finally {
            closeContext(batchContext);
        }
    }

    private int processPublicationTasks() {
        List<ScopusPublicationUpdate> tasks =
                taskRepo.findByStatusOrderByInitiatedDate(Status.PENDING);

        if (tasks.isEmpty()) return 0;

        int processedTasks = 0;

        for (ScopusPublicationUpdate t : tasks) {
            AutoCloseable taskContext = SchedulerCorrelationSupport.withSchedulerContext(
                    "scopus.publication.update",
                    String.valueOf(t.getId()),
                    "start"
            );
            long taskStartedAt = System.currentTimeMillis();
            try {
                runOnePublicationUpdate(t);
                processedTasks++;
                MDC.put("phase", "complete");
                log.info("Publication task {} completed in {} ms", t.getId(), System.currentTimeMillis() - taskStartedAt);
            } catch (Exception e) {
                MDC.put("phase", "failed");
                log.error("Publication task {} failed", t.getId(), e);
                t.setStatus(Status.FAILED);
                t.setMessage("FAILED: " + e.getMessage());
                t.setExecutionDate(LocalDate.now(Z).toString());
                taskRepo.save(t);
            } finally {
                closeContext(taskContext);
            }
        }
        return processedTasks;
    }

    private void runOnePublicationUpdate(ScopusPublicationUpdate task) {
        MDC.put("phase", "progress");
        task.setStatus(Status.IN_PROGRESS);
        task.setMessage("Starting");
        taskRepo.save(task);

        final String authorScopusId = task.getScopusId();
        String fromDate = computeFromDate(authorScopusId);

        String cursor = null;
        int imported = 0;

        do {
            AuthorWorksRequest req = buildRequest(authorScopusId, fromDate, cursor);
            AuthorWorksResponse resp = callPython(req);

            if (resp.getItems() != null) {
                for (JsonNode item : resp.getItems()) {
                    Publication publication = scopusDataService.createPublicationFromJson(item);
                    scopusDataService.handleAffiliations(publication, item);
                    scopusDataService.handleAuthors(publication, item);
                    scopusDataService.handleVenue(publication, item);
                    scopusDataService.handleFunding(publication, item);

                    Optional<Publication> existing = publicationRepo.findByEid(publication.getEid());
                    if (existing.isEmpty()) {
                        publicationRepo.insert(publication);
                        imported++;
                    }
                }
            }

            cursor = resp.getNext_cursor();
            log.debug("Publication task {} progress: imported={}, nextCursorPresent={}",
                    task.getId(), imported, cursor != null && !cursor.isBlank());
        } while (cursor != null && !cursor.isBlank());

        MDC.put("phase", "complete");
        task.setStatus(Status.COMPLETED);
        task.setMessage("Imported " + imported + " items since " + fromDate);
        task.setExecutionDate(LocalDate.now(Z).toString());
        taskRepo.save(task);
    }



    private int processCitationTasks() {
        List<ScopusCitationsUpdate> tasks =
                citationsTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING);

        if (tasks.isEmpty()) return 0;

        int processedTasks = 0;

        for (ScopusCitationsUpdate t : tasks) {
            AutoCloseable taskContext = SchedulerCorrelationSupport.withSchedulerContext(
                    "scopus.citation.update",
                    String.valueOf(t.getId()),
                    "start"
            );
            long taskStartedAt = System.currentTimeMillis();
            try {
                runOneCitationsUpdate(t);
                processedTasks++;
                MDC.put("phase", "complete");
                log.info("Citations task {} completed in {} ms", t.getId(), System.currentTimeMillis() - taskStartedAt);
            } catch (Exception e) {
                MDC.put("phase", "failed");
                log.error("Citations task {} failed", t.getId(), e);
                t.setStatus(Status.FAILED);
                t.setMessage("FAILED: " + e.getMessage());
                t.setExecutionDate(LocalDate.now(Z).toString());
                citationsTaskRepo.save(t);
            } finally {
                closeContext(taskContext);
            }
        }
        return processedTasks;
    }

    private void runOneCitationsUpdate(ScopusCitationsUpdate task) {
        MDC.put("phase", "progress");
        task.setStatus(Status.IN_PROGRESS);
        task.setMessage("Starting citations update");
        citationsTaskRepo.save(task);

        final String authorScopusId = task.getScopusId();

        // 1) compute last citation date per EID **for this author only**
        Map<String, String> eidLastDate = computeEidLastCitationDatesForAuthor(authorScopusId);
        if (eidLastDate.isEmpty()) {
            task.setStatus(Status.COMPLETED);
            task.setMessage("No publications found for author " + authorScopusId + ", nothing to update.");
            task.setExecutionDate(LocalDate.now(Z).toString());
            citationsTaskRepo.save(task);
            return;
        }

        // 2) prepare request
        CitationsByEidRequest req = new CitationsByEidRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setEidLastDate(eidLastDate);
        req.setPageSizePerEid(100);
        req.setIncludeEnrichment(true);

        // 3) call Python service
        CitationsByEidResponse resp = callPythonCitations(req);
        log.debug("Citations task {} progress: requestId={}, eids={}",
                task.getId(), req.getRequestId(), req.getEidLastDate().size());

        int importedPublications = 0;
        int createdCitations = 0;

        Map<String, List<JsonNode>> byEid = resp.getByEid();
        if (byEid != null) {
            for (Map.Entry<String, List<JsonNode>> entry : byEid.entrySet()) {
                String citedEid = entry.getKey();
                List<JsonNode> citingItems = entry.getValue();

                if (citingItems == null || citingItems.isEmpty()) continue;

                Optional<Publication> citedOpt = publicationRepo.findByEid(citedEid);
                if (citedOpt.isEmpty()) {
                    log.warn("Cited publication with EID {} not found locally, skipping its citations", citedEid);
                    continue;
                }
                Publication cited = citedOpt.get();

                for (JsonNode item : citingItems) {
                    Publication citing = scopusDataService.createPublicationFromJson(item);
                    scopusDataService.handleAffiliations(citing, item);
                    scopusDataService.handleAuthors(citing, item);
                    scopusDataService.handleVenue(citing, item);
                    scopusDataService.handleFunding(citing, item);

                    // upsert citing publication by EID
                    Optional<Publication> existing = publicationRepo.findByEid(citing.getEid());
                    if (existing.isPresent()) {
                        citing = existing.get();
                    } else {
                        citing = publicationRepo.insert(citing);
                        importedPublications++;
                    }

                    Citation citation = new Citation();
                    citation.setCitedId(cited.getId());
                    citation.setCitingId(citing.getId());

                    if (citationRepo
                            .findByCitedIdAndCitingId(cited.getId(), citing.getId())
                            .isEmpty()) {
                        citationRepo.insert(citation);
                        createdCitations++;
                    }
                }
                cited.setCitedbyCount((int) citationRepo.countAllByCitedId(cited.getId()));
                publicationRepo.save(cited);
            }
        }

        task.setStatus(Status.COMPLETED);
        task.setMessage("Author " + authorScopusId + ": imported/updated " +
                importedPublications + " citing publications and " + createdCitations + " citation links.");
        task.setExecutionDate(LocalDate.now(Z).toString());
        citationsTaskRepo.save(task);
    }

    private void closeContext(AutoCloseable context) {
        try {
            context.close();
        } catch (Exception e) {
            log.warn("Failed to close scheduler correlation context cleanly", e);
        }
    }

    private Map<String, String> computeEidLastCitationDatesForAuthor(String authorScopusId) {
        // 1) All publications by this author
        List<Publication> authorPubs = publicationRepo.findAllByAuthorsContaining(authorScopusId);
        if (authorPubs.isEmpty()) {
            return Collections.emptyMap();
        }

        // id -> Publication for author’s publications (for cited side)
        Map<String, Publication> byId = new HashMap<>();
        for (Publication p : authorPubs) {
            if (p.getId() != null) {
                byId.put(p.getId(), p);
            }
        }

        // 2) All citations where any of these pubs is the **cited** one
        List<String> citedIds = authorPubs.stream()
                .map(Publication::getId)
                .filter(Objects::nonNull)
                .toList();

        List<Citation> citations = citationRepo.findAllByCitedIdIn(citedIds);

        // 3) Load all citing publications for those citations (more efficient than findAll())
        Set<String> citingIds = citations.stream()
                .map(Citation::getCitingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Publication> citingPubs = citingIds.isEmpty()
                ? Collections.emptyList()
                : publicationRepo.findAllByIdIn(new ArrayList<>(citingIds));

        Map<String, Publication> citingById = new HashMap<>();
        for (Publication p : citingPubs) {
            if (p.getId() != null) {
                citingById.put(p.getId(), p);
            }
        }

        // 4) Initialize map with all author EIDs, default last-date = null
        Map<String, String> lastDateByEid = new HashMap<>();
        for (Publication p : authorPubs) {
            if (p.getEid() != null) {
                lastDateByEid.put(p.getEid(), null);
            }
        }

        // 5) For each citation, update the last citing date for the cited EID
        for (Citation c : citations) {
            Publication cited = byId.get(c.getCitedId());
            Publication citing = citingById.get(c.getCitingId());
            if (cited == null || citing == null) {
                continue;
            }

            String citingCover = citing.getCoverDate();
            if (citingCover == null || citingCover.isBlank()) {
                continue;
            }

//            LocalDate citingDate = parseCoverDate(citingCover);
            LocalDate citingDate = parseCoverDate("1970-01-01"); // treat missing dates as very old
            String citedEid = cited.getEid();
            if (citedEid == null) continue;

            String existing = lastDateByEid.get(citedEid);
            if (existing == null || parseCoverDate(existing).isBefore(citingDate)) {
                lastDateByEid.put(citedEid, citingDate.format(ISO));
            }
        }

        return lastDateByEid;
    }




    private String computeFromDate(String authorScopusId) {
        // Try repo shortcut
        Optional<Publication> latest = publicationRepo.findTopByAuthorsContainsOrderByCoverDateDesc(authorScopusId);
        LocalDate base;
        if (latest.isPresent()) {
            String cd = latest.get().getCoverDate(); // expected "yyyy-MM-dd" or "yyyy-MM"
            base = parseCoverDate(cd);
        } else {
            // If no prior data, go back 5 years as a sane default
            base = LocalDate.now(Z).minusYears(5);
        }
        base = LocalDate.now(Z).minusYears(50); // TEMPORARY OVERRIDE TO REIMPORT ALL DATA
        return base.minusYears(1).format(ISO);
    }

    private LocalDate parseCoverDate(String s) {
        try {
            if (s == null || s.isBlank()) return LocalDate.now(Z);
            if (s.length() == 10) return LocalDate.parse(s);
            if (s.length() == 7)  return LocalDate.parse(s + "-01");
            if (s.length() == 4)  return LocalDate.parse(s + "-01-01");
            return LocalDate.now(Z);
        } catch (Exception e) {
            return LocalDate.now(Z);
        }
    }

    private CitationsByEidResponse callPythonCitations(CitationsByEidRequest req) {
        return scopusPythonClient.post()
                .uri("/v1/citations/by-eid")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(CitationsByEidResponse.class)
                .onErrorResume(ex -> {
                    String msg = "Python citations service error: " + ex.getMessage();
                    log.error(msg);
                    return Mono.error(new RuntimeException(msg, ex));
                })
                .block();
    }


    private AuthorWorksRequest buildRequest(String authorId, String fromDate, String cursor) {
        AuthorWorksRequest req = new AuthorWorksRequest();
        req.setRequest_id(UUID.randomUUID().toString());
        req.setAuthor_id(authorId);
        req.setFrom_date(fromDate);
        req.setInclude_enrichment(true);
        req.setFormat("legacy");
        AuthorWorksRequest.Paging p = new AuthorWorksRequest.Paging();
        p.setPage_size(pageSize);
        p.setCursor(cursor);
        req.setPaging(p);
        return req;
    }

    private AuthorWorksResponse callPython(AuthorWorksRequest req) {
        return scopusPythonClient.post()
                .uri("/v1/author-works")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(AuthorWorksResponse.class)
                .onErrorResume(ex -> {
                    String msg = "Python service error: " + ex.getMessage();
                    log.error(msg);
                    return Mono.error(new RuntimeException(msg, ex));
                })
                .block();
    }
}
