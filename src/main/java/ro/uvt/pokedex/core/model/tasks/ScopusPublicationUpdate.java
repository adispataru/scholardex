package ro.uvt.pokedex.core.model.tasks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@EqualsAndHashCode(callSuper = true)
@Data
@Document(collection = "scholardex.tasks.scopusPublicationUpdate")
public class ScopusPublicationUpdate extends Task {
    @Id
    private String id;
    private String scopusId;

    /** One of: SINCE_LAST_UPDATE | PERIOD | FULL. Null is treated as SINCE_LAST_UPDATE (backwards-compat). */
    private String syncMode;
    /** Inclusive start year for PERIOD mode (e.g. 2017). */
    private Integer startYear;
    /** Inclusive end year for PERIOD mode (e.g. 2023). */
    private Integer endYear;
}
