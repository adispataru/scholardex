package ro.uvt.pokedex.core.model.reporting.transfer.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * A computed total written into a label-bearing cell of a DOCX template (the template carries no
 * formulas). The renderer locates the cell whose text contains {@code marker} and writes the value
 * for {@code totalKey} after the trailing "=". {@code totalKey} indexes
 * {@code ReportInstanceSnapshot.totals} — a per-role / per-block total taken from the run's
 * dedicated indicator scores (e.g. {@code S} ← role total, {@code C1_UVT} ← that block's total).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingDocxTotal {
    /** Substring identifying the target cell, e.g. "S =", "C =", "Total punctaj  C1_UVT". */
    private String marker;
    /** Key into {@code ReportInstanceSnapshot.totals}. */
    private String totalKey;
    /** Optional: when the marker cell holds two labels (e.g. "S = … Srecent ="), which "=" to fill (0-based). */
    private int equalsIndex = 0;
}
