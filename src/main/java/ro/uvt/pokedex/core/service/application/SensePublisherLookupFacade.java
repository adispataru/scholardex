package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceBookService;

import java.util.Optional;

/**
 * Read-side SENSE publisher lookup for the publication wizard's live classification badge
 * (keeps the reporting-layer scorer out of the controller layer, mirroring
 * {@link CoreConferenceLookupFacade}). Delegates to the book scorer's own cached fuzzy
 * resolution so the badge always matches what scoring will decide.
 */
@Service
@RequiredArgsConstructor
public class SensePublisherLookupFacade {

    private final ComputerScienceBookService computerScienceBookService;

    public Optional<SenseBookRanking> matchByPublisher(String publisher) {
        return computerScienceBookService.matchByPublisher(publisher);
    }
}
