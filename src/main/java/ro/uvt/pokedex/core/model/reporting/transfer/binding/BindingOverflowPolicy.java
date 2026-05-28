package ro.uvt.pokedex.core.model.reporting.transfer.binding;

public enum BindingOverflowPolicy {
    /** Insert rows past {@code maxRows}, shifting the aggregation block down and letting POI's
     *  FormulaShifter extend SUMIF/COUNTIF/cross-sheet references that ended at the original
     *  last data row. Default. */
    EXPAND,
    /** Log a warning and write only the first {@code maxRows} entries. */
    WARN_AND_TRUNCATE,
    /** Fail the render. */
    ERROR
}
