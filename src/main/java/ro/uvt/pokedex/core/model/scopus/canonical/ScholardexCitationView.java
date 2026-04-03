package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;

@Data
public class ScholardexCitationView {
    private String id;
    private String citedPublicationId;
    private String citingPublicationId;

    public String getCitedId() {
        return citedPublicationId;
    }

    public void setCitedId(String citedId) {
        this.citedPublicationId = citedId;
    }

    public String getCitingId() {
        return citingPublicationId;
    }

    public void setCitingId(String citingId) {
        this.citingPublicationId = citingId;
    }
}
