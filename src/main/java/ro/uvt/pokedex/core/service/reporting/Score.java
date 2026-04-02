package ro.uvt.pokedex.core.service.reporting;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class Score {
    private double score;
    private int year;
    private String category;
    private String quarter;
    private String scoringSource;
    private Map<String, Object> scoringInfo = new HashMap<>();
    private double authorScore;
    private Map<String, String> errors = new HashMap<>();
    private Map<String, Object> extra = new HashMap<>();
    private String details;
}
