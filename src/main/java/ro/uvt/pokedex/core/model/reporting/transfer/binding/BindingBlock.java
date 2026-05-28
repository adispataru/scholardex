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
}
