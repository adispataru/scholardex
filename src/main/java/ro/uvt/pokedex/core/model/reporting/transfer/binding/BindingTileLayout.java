package ro.uvt.pokedex.core.model.reporting.transfer.binding;

/** How a TILED_SHEETS role lays its tiles out in the rendered workbook (H106 S2). */
public enum BindingTileLayout {
    /** One cloned sheet per tile ("Citari-01", "Citari-02", …) — the original H50.2 export shape. */
    SHEET_PER_TILE,
    /**
     * Every tile stacked vertically in ONE sheet, each tile a copy of the template block (title row through
     * the TOTAL row) — the shape real researcher-filled Fișe use and the parser already accepts.
     */
    STACKED
}
