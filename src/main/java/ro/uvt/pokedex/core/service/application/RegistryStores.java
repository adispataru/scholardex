package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.registry.RegistryEntry;
import ro.uvt.pokedex.core.model.registry.RegistryItem;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.RegistryEntryRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;
import ro.uvt.pokedex.core.service.reporting.RegistryRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * H144 — where each registry keeps its entries: artistic events in their own collection (the CNFIS list), the other
 * kinds in {@code scholardex.registry_entries}; the records that name a kind's entities; the reload after a decision.
 */
@Component
@RequiredArgsConstructor
public class RegistryStores {

    private final ArtisticEventRepository artisticEventRepository;
    private final RegistryEntryRepository registryEntryRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ArtisticEventRankRegistrar artisticEventRankRegistrar;
    private final RegistryRegistrar registryRegistrar;

    public List<RegistryItem> all(RegistryKind kind) {
        return kind == RegistryKind.ARTISTIC_EVENT
                ? new ArrayList<>(artisticEventRepository.findAll())
                : new ArrayList<>(registryEntryRepository.findAllByKind(kind));
    }

    public Optional<RegistryItem> byId(RegistryKind kind, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return kind == RegistryKind.ARTISTIC_EVENT
                ? artisticEventRepository.findById(id).map(RegistryItem.class::cast)
                : registryEntryRepository.findById(id).filter(e -> e.getKind() == kind).map(RegistryItem.class::cast);
    }

    public RegistryItem create(RegistryKind kind) {
        return kind == RegistryKind.ARTISTIC_EVENT ? new ArtisticEvent() : RegistryEntry.of(kind);
    }

    public void save(RegistryItem item) {
        if (item instanceof ArtisticEvent event) {
            artisticEventRepository.save(event);
        } else if (item instanceof RegistryEntry entry) {
            registryEntryRepository.save(entry);
        }
    }

    /** Every record that names an entity of this kind (the experts' queue is built from them). */
    public List<ActivityInstance> recordsNaming(RegistryKind kind) {
        return switch (kind) {
            case ARTISTIC_EVENT -> activityInstanceRepository.findAllNamingAnEvent();
            case SCIENTIFIC_EVENT -> activityInstanceRepository.findAllNamingAConference();
            case ORGANIZATION -> activityInstanceRepository.findAllNamingAnOrganization();
            case AWARD -> activityInstanceRepository.findAllNamingAnAward();
        };
    }

    /** The name a record gives in the kind's reference field, or "". */
    public static String nameIn(ActivityInstance record, RegistryKind kind) {
        if (record.getReferenceFields() == null) {
            return "";
        }
        String name = record.getReferenceFields().get(kind.referenceField());
        return name == null ? "" : name;
    }

    /** Reloads the registry the scoring reads, after a decision. */
    public void refresh(RegistryKind kind) {
        if (kind == RegistryKind.ARTISTIC_EVENT) {
            artisticEventRankRegistrar.refresh();
        } else {
            registryRegistrar.refresh();
        }
    }
}
