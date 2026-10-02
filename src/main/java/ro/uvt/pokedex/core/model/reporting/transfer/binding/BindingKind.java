package ro.uvt.pokedex.core.model.reporting.transfer.binding;

public enum BindingKind {
    FIXED_TABLE,
    STACKED_BLOCKS,
    TILED_SHEETS,
    /**
     * H142: a fixed table whose rows each list ALL their items in ONE cell (one per line), with the row's
     * points beside it — the layout of the faculties' fișe de verificare (e.g. Music). Each block is one
     * row: {@code keyColumn} holds the items, {@code scoreColumn} the points, and {@code labelColumn} the
     * row's label ("1.1. Tratat…"), by which an uploaded file's row is found again.
     */
    ITEMS_IN_CELL
}
