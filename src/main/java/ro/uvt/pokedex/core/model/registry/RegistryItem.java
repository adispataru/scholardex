package ro.uvt.pokedex.core.model.registry;

import java.time.Instant;
import java.util.List;

/**
 * H144 — what the experts' engine reads and writes on an entry of any registry: an artistic event of the CNFIS list
 * ({@link ro.uvt.pokedex.core.model.ArtisticEvent}, its own collection) or a conference, organisation or award
 * ({@link RegistryEntry}). Levels, categories and criteria are the kind's own names ({@link RegistryKind}).
 */
public interface RegistryItem {

    RegistryKind registryKind();

    String getId();

    String getName();

    void setName(String name);

    String getDomainId();

    void setDomainId(String domainId);

    List<String> getAliases();

    void setAliases(List<String> aliases);

    RegistryStatus getStatus();

    void setStatus(RegistryStatus status);

    String getLevel();

    void setLevel(String level);

    String getCategory();

    void setCategory(String category);

    List<String> getCriteria();

    void setCriteria(List<String> criteria);

    String getCountry();

    void setCountry(String country);

    String getBasis();

    void setBasis(String basis);

    String getNote();

    void setNote(String note);

    String getSource();

    void setSource(String source);

    String getProposedBy();

    void setProposedBy(String proposedBy);

    Instant getProposedAt();

    void setProposedAt(Instant proposedAt);

    String getDecidedBy();

    void setDecidedBy(String decidedBy);

    Instant getDecidedAt();

    void setDecidedAt(Instant decidedAt);

    String getMergedInto();

    void setMergedInto(String mergedInto);

    List<RegistryChange> getHistory();

    void setHistory(List<RegistryChange> history);

    /** Ranked and counted. */
    default boolean isConfirmed() {
        return getStatus() == null || getStatus() == RegistryStatus.CONFIRMED;
    }

    /** The basis shown to everyone: the recorded one (the CNFIS list for an artistic event without a status). */
    default String effectiveBasis() {
        return getBasis();
    }
}
