package ro.uvt.pokedex.core.model.reporting.transfer.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * A computed total written into a label-bearing cell of a DOCX template (the template carries no
 * formulas). Two placement modes:
 * <ul>
 *   <li><b>Inline</b> (default): locate the cell whose text contains {@code marker} and write the value
 *       after the trailing "=" (see {@code equalsIndex}). Used by FV Matematică ("S = …").</li>
 *   <li><b>Cell-target</b> ({@code cellIndex} set): locate the <i>row</i> whose text contains
 *       {@code marker} (scoped to the role's {@code tableIndex}) and write the value into that row's
 *       {@code cellIndex}-th cell. Used by FV FEAA, whose totals/summary rows have a dedicated empty
 *       value cell (e.g. "… | Total punctaj … | [value]" or "Punctaj final (S) | … | [obtained]").</li>
 * </ul>
 * {@code totalKey} indexes {@code ReportInstanceSnapshot.totals} — a per-role / per-block total from
 * the run's dedicated indicator scores.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingDocxTotal {
    /** Substring identifying the target cell (inline mode) or row (cell-target mode). */
    private String marker;
    /** Key into {@code ReportInstanceSnapshot.totals}. */
    private String totalKey;
    /** Inline mode: when the marker cell holds two labels (e.g. "S = … Srecent ="), which "=" to fill (0-based). */
    private int equalsIndex = 0;
    /** Cell-target mode: 0-based index of the value cell within the marker's row (within the role's table).
     *  When set, the value is written into that cell instead of after an "=". */
    private Integer cellIndex;
}
