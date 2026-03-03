package ro.uvt.pokedex.core.service.application.model;

import java.util.Map;

public record GroupIndividualReportViewModel(
        String redirect,
        Map<String, Object> attributes
) {
}
