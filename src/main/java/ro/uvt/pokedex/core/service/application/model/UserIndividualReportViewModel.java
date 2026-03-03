package ro.uvt.pokedex.core.service.application.model;

import java.util.Map;

public record UserIndividualReportViewModel(
        String redirect,
        Map<String, Object> attributes
) {
}
