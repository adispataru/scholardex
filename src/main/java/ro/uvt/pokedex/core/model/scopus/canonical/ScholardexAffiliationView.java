package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;

import java.time.Instant;
import java.util.Objects;

@Data
public class ScholardexAffiliationView {
    private String id;
    private String name;
    private String city;
    private String country;
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;

    public String getAfid() {
        return id;
    }

    public void setAfid(String afid) {
        this.id = afid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScholardexAffiliationView that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
