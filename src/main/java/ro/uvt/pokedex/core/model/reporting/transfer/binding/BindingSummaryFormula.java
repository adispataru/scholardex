package ro.uvt.pokedex.core.model.reporting.transfer.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingSummaryFormula {
    private String cell;
    private BindingSummaryRule rule;
    private String tileCell;
}
