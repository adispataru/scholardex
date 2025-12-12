package ro.uvt.pokedex.core.model.reporting;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.activities.Activity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@Document(collection = "indicators")
public class Indicator {
    @Id
    private String id;
    private String name;
    private Type outputType;
    private String formula;
    private Strategy scoringStrategy;
    private String yearRange;
    private String scoreYearRange;
    @DBRef
    private Domain domain;
    @DBRef
    private Activity activity;
    private Selector selector;

    public static enum Selector {
        ALL,
        TOP_10
    }

    public static List<Integer> parseYearRange(String yearRange, int itemYear) {
        List<Integer> years = new ArrayList<>();
        if (yearRange == null || yearRange.isEmpty()) {
            return years;
        }
        if(yearRange.equals("*")){
            int currentYear = java.time.LocalDate.now().getYear();
            for (int i = 1990; i <= currentYear; i++) {
                years.add(i);
            }
            return years;
        }

        String[] parts = yearRange.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.contains("->")) {
                String[] range = part.split("->");
                try {
                    int start = parseYear(range[0].trim(), itemYear);
                    int end = parseYear(range[1].trim(), itemYear);
                    for (int i = start; i <= end; i++) {
                        years.add(i);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid range format: " + part);
                }
            } else {
                try {
                    years.add(parseYear(part, itemYear));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid year format: " + part);
                }
            }
        }
        return years;
    }

    private static int parseYear(String year, int itemYear) {
        if (year.equals("IY")) {
            return itemYear;
        } else if (year.startsWith("IY")) {
            return itemYear + Integer.parseInt(year.substring(2));
        } else {
            return Integer.parseInt(year);
        }
    }

    public enum Type{
        PUBLICATIONS,
        PUBLICATIONS_MAIN_AUTHOR,
        PUBLICATIONS_COAUTHOR,
        CITATIONS,
        CITATIONS_EXCLUDE_SELF,
        GENERIC_ACTIVITIES,
        ACTIVITY_FORUM,
        ACTIVITY_EVENT,
        ACTIVITY_PROJECT,
        ACTIVITY_UNIVERSITY
    }

    public enum Strategy{
        GENERIC_ACTIVITY,
        GENERIC_COUNT,
        CS_CONFERENCE,
        CS_JOURNAL,
        CS_SENSE,
        CS,
        IMPACT_FACTOR,
        RIS,
        AIS,
        ECONOMICS_JOURNAL_AIS,
        UNI_RANKING,
        CNCSIS,
        ART_EVENT
    }
}