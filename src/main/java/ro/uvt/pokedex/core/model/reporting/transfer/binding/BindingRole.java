package ro.uvt.pokedex.core.model.reporting.transfer.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single role within a template binding. Some fields apply only to certain kinds; the loader
 * validates kind-specific constraints. Keeping this as one POJO (rather than a Jackson polymorphic
 * hierarchy) lets the binding JSON stay shallow and the loader's error messages stay specific.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingRole {
    private String roleKey;
    private BindingKind kind;

    // FIXED_TABLE fields
    private String sheet;
    private Integer firstDataRow;
    private Integer maxRows;
    private BindingOverflowPolicy overflowPolicy;
    private Map<String, BindingColumn> columns = new LinkedHashMap<>();

    // Import/verification: which columns identify a row and carry its per-item score / fields.
    private String keyColumn;          // e.g. "C" (title)
    private String scoreColumn;        // e.g. "J" (Punctaj P)
    private String categoryColumn;     // e.g. "H" (Categorie forum) — for field-level diff
    private String authorCountColumn;  // e.g. "I" (Nr. autori) — for field-level diff

    // STACKED_BLOCKS fields
    private String activityMatch;
    private String groupingKey;
    private String blockHeaderMarker;  // text every block header shares (e.g. "Justificări pentru indicatorul")
    private List<BindingBlock> blocks = new ArrayList<>();
    private Map<String, BindingColumn> blockColumns = new LinkedHashMap<>();
    private Map<String, BindingColumn> totalRowColumns = new LinkedHashMap<>();

    // TILED_SHEETS fields
    private String templateSheet;
    private String summarySheet;
    private String perTileTitleCell;
    private String perTileTitleTemplate;
    private Map<String, String> perTileScalar = new LinkedHashMap<>();
    private Integer innerTableFirstDataRow;
    private Integer innerTableMaxRows;
    private Map<String, BindingColumn> innerColumns = new LinkedHashMap<>();
    private String innerScoreColumn;  // per-citing-row Punctaj column (raw points) for the breakdown
    private String tileTotalLabel;    // label in the key column marking the tile's grand-total row (e.g. "TOTAL")
    private String sheetNameTemplate;
    private List<BindingSummaryFormula> summaryFormulas = new ArrayList<>();
}
