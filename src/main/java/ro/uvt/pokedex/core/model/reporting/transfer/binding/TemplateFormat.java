package ro.uvt.pokedex.core.model.reporting.transfer.binding;

/**
 * Output format a {@link TemplateBinding} drives. Determines which renderer (and addressing
 * convention) applies: XLSX bindings address cells by sheet + column-letter and may lean on the
 * template's own formulas; DOCX bindings address Word tables by index + cell-index and carry all
 * final values themselves (no in-document formulas).
 */
public enum TemplateFormat {
    XLSX,
    DOCX
}
