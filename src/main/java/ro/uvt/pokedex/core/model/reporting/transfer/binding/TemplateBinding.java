package ro.uvt.pokedex.core.model.reporting.transfer.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TemplateBinding {
    private String reportTypeKey;
    private String templateResource;
    /** Output format this binding drives. Defaults to XLSX for back-compat with existing bindings. */
    private TemplateFormat templateFormat = TemplateFormat.XLSX;
    private int schemaVersion;
    private List<BindingScalarCell> scalarCells = new ArrayList<>();
    private List<BindingRole> roles = new ArrayList<>();

    /** Template placeholder texts (e.g. "Titlu articol") that an unfilled row may carry in its key
     *  column; the score parser skips any data row whose key matches one of these. */
    private List<String> ignoredKeyValues = new ArrayList<>();
}
