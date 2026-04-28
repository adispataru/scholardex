package ro.uvt.pokedex.core.service.scopus;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidRequest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ScopusCitationSyncPlanner {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int DEFAULT_PAGE_SIZE_PER_EID = 100;

    private final ScopusPublicationSyncPlanner publicationPlanner;

    ScopusCitationSyncPlanner() {
        this(new ScopusPublicationSyncPlanner());
    }

    ScopusCitationSyncPlanner(ScopusPublicationSyncPlanner publicationPlanner) {
        this.publicationPlanner = publicationPlanner;
    }

    List<String> citedPublicationIds(List<ScholardexPublicationView> authorPublications) {
        return authorPublications.stream()
                .map(ScholardexPublicationView::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    List<String> citingPublicationIds(List<ScholardexCitationView> citations) {
        return citations.stream()
                .map(ScholardexCitationView::getCitingId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    Map<String, String> computeEidLastCitationDates(
            List<ScholardexPublicationView> authorPublications,
            List<ScholardexCitationView> citations,
            List<ScholardexPublicationView> citingPublications
    ) {
        if (authorPublications.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, ScholardexPublicationView> authorPublicationsById = new HashMap<>();
        Map<String, String> lastDateByEid = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : authorPublications) {
            if (publication.getId() != null) {
                authorPublicationsById.put(publication.getId(), publication);
            }
            if (publication.getEid() != null) {
                lastDateByEid.put(publication.getEid(), null);
            }
        }

        Map<String, ScholardexPublicationView> citingPublicationsById = new HashMap<>();
        for (ScholardexPublicationView publication : citingPublications) {
            if (publication.getId() != null) {
                citingPublicationsById.put(publication.getId(), publication);
            }
        }

        for (ScholardexCitationView citation : citations) {
            ScholardexPublicationView cited = authorPublicationsById.get(citation.getCitedId());
            ScholardexPublicationView citing = citingPublicationsById.get(citation.getCitingId());
            if (cited == null || citing == null) {
                continue;
            }

            Optional<LocalDate> citingDate = publicationPlanner.parseCoverDate(citing.getCoverDate());
            if (citingDate.isEmpty() || cited.getEid() == null) {
                continue;
            }

            String existing = lastDateByEid.get(cited.getEid());
            Optional<LocalDate> existingDate = publicationPlanner.parseCoverDate(existing);
            if (existingDate.isEmpty() || existingDate.get().isBefore(citingDate.get())) {
                lastDateByEid.put(cited.getEid(), citingDate.get().format(ISO));
            }
        }

        return lastDateByEid;
    }

    Map<String, String> resolveEidLastDates(ScopusCitationsUpdate task, Map<String, String> computedDates) {
        if ("FULL".equals(task.getSyncMode())) {
            Map<String, String> fullDates = new LinkedHashMap<>();
            computedDates.keySet().forEach(eid -> fullDates.put(eid, ""));
            return fullDates;
        }
        if ("PERIOD".equals(task.getSyncMode()) && task.getStartYear() != null) {
            String periodStart = task.getStartYear() + "-01-01";
            Map<String, String> periodDates = new LinkedHashMap<>();
            computedDates.forEach((eid, lastDate) ->
                    periodDates.put(eid, lastDate != null && lastDate.compareTo(periodStart) > 0 ? lastDate : periodStart)
            );
            return periodDates;
        }
        return new LinkedHashMap<>(computedDates);
    }

    CitationsByEidRequest buildRequest(Map<String, String> eidLastDate) {
        return buildRequest(eidLastDate, DEFAULT_PAGE_SIZE_PER_EID);
    }

    CitationsByEidRequest buildRequest(Map<String, String> eidLastDate, int pageSizePerEid) {
        CitationsByEidRequest request = new CitationsByEidRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setEidLastDate(eidLastDate);
        request.setPageSizePerEid(pageSizePerEid);
        return request;
    }
}
