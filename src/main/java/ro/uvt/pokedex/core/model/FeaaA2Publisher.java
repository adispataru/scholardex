package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A publisher from the CNATDCU "Edituri de prestigiu recunoscut în domeniul științelor sociale"
 * (lista A2, Panel 4) — the list FEAA Anexa 1 b) references as "edituri de prestigiu național".
 * Contains BOTH sections of the official xls (edituri românești + edituri din străinătate; the
 * UN-organisations aggregate row is split into its member names so exact matching can work). A flat
 * name-matched allowlist like {@link FeaaAnexa1Publisher}; source: cnatdcu.ro A2_Panel41.xls,
 * mirrored at {@code data/standards/economie/A2_Panel41.xls}.
 */
@Data
@Document(collection = "scholardex.feaaA2")
public class FeaaA2Publisher {
    @Id
    private String id;
    private Integer nr;
    private String name;
}
