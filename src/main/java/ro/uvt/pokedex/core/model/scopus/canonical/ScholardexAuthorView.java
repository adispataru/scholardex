package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class ScholardexAuthorView {
    private String id;
    private String name;
    private List<String> affiliationIds = new ArrayList<>();
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;
}
