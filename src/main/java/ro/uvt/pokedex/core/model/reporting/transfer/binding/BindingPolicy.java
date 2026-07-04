package ro.uvt.pokedex.core.model.reporting.transfer.binding;

public enum BindingPolicy {
    WRITE,
    WRITE_SCORE,
    SKIP_FORMULA,
    MANUAL,
    // H81: write a named indicator's computed total (the run's per-role total, keyed by the scalar cell's `source`
    // role) into a fixed template cell. Lets a scalar criterion (e.g. "Număr proiecte ca director") be a real
    // platform-computed indicator rather than an Excel COUNTIF over free-text cells.
    INDICATOR_TOTAL
}
