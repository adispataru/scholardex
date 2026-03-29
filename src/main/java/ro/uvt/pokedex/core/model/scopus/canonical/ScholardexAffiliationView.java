package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;

import java.time.Instant;

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
}
