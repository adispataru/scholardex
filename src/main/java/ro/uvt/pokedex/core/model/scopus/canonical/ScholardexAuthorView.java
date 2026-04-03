package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Data
public class ScholardexAuthorView {
    private String id;
    private String name;
    private List<String> affiliationIds = new ArrayList<>();
    private List<ScholardexAffiliationView> affiliations = new ArrayList<>();
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScholardexAuthorView that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
