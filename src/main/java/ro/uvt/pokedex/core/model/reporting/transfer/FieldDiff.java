package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldDiff {
    private String fieldName;
    private String existingValue;
    private String incomingValue;
}
