package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The country an OpenAlex source (journal, book series, …) is published in, as OpenAlex states it for the
 * host organization. A side table that is never rebuilt: the answer is fetched from the {@code /sources}
 * endpoint once per venue, and "OpenAlex has no country for it" is an answer too — it is asked again only
 * after a while.
 *
 * <p>Kept apart from {@link OpenAlexSourceFact} on purpose: that collection is derived from the works dumps
 * and rewritten by the import, which would drop a field it does not know.</p>
 */
@Data
@Document(collection = "openalex.source_countries")
public class OpenAlexSourceCountry {

    /** Stripped OpenAlex source id, e.g. {@code S4210202905}. */
    @Id
    private String id;
    /** ISO 3166 alpha-2, upper case; null when OpenAlex gives none. */
    private String countryCode;
    private String displayName;
    private String hostOrganizationName;
    private Instant checkedAt;
}
