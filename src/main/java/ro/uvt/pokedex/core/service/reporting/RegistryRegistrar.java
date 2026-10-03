package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.repository.RegistryEntryRepository;

/**
 * Hands the registries of conferences, organisations and awards to {@link RegistrySupport} at startup (loaded on first
 * use); {@link #refresh()} reloads them after an expert's decision.
 */
@Service
@RequiredArgsConstructor
public class RegistryRegistrar {

    private final RegistryEntryRepository registryEntryRepository;

    @PostConstruct
    void init() {
        RegistrySupport.registerLoader(registryEntryRepository::findAll);
    }

    /** Reload now, after the registries changed. */
    public void refresh() {
        RegistrySupport.registerLoader(registryEntryRepository::findAll);
    }
}
