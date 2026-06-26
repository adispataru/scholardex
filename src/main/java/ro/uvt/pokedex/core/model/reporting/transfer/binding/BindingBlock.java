package ro.uvt.pokedex.core.model.reporting.transfer.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingBlock {
    private String activityName;
    private Integer headerRow;
    private Integer firstDataRow;
    private Integer totalRow;
    /**
     * H65: the exact text that marks this block's total row, when the template doesn't follow the default
     * {@code "Total punctaj <activityName>"} convention (e.g. the physics fišă uses "Punctaj total indicator A1").
     * Null → fall back to the default convention.
     */
    private String totalMarker;
}
