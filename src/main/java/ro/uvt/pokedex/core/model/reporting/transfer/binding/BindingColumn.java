package ro.uvt.pokedex.core.model.reporting.transfer.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingColumn {
    private BindingPolicy policy;
    private String source;
}
