package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;

/**
 * Hands the registry of artistic events to {@link ArtisticEventRankSupport} at startup. The support loads it on
 * first use; {@link #refresh()} reloads it after the registry changes (the artistic-events import).
 */
@Service
@RequiredArgsConstructor
public class ArtisticEventRankRegistrar {

    private final ArtisticEventRepository artisticEventRepository;

    @PostConstruct
    void init() {
        ArtisticEventRankSupport.registerLoader(artisticEventRepository::findAll);
    }

    /** Reload now, after the registry changed. */
    public void refresh() {
        ArtisticEventRankSupport.registerLoader(artisticEventRepository::findAll);
    }
}
