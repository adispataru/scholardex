package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;

import java.util.List;

/**
 * Read-side lookup over the CORE conference rankings for the workspace conference picker
 * (mirrors {@link PostgresScholardexProjectReadPort} keeping the repository out of the
 * controller layer).
 */
@Service
@RequiredArgsConstructor
public class CoreConferenceLookupFacade {

    private final CoreConferenceRankingRepository coreConferenceRankingRepository;

    /** Autocomplete search: acronym prefix OR name substring, case-insensitive, capped at 20. */
    public List<CoreConferenceRanking> searchByAcronymOrName(String query) {
        return coreConferenceRankingRepository
                .findTop20ByAcronymStartingWithIgnoreCaseOrNameContainingIgnoreCaseOrderByAcronymAsc(query, query);
    }
}
