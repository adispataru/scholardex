package ro.uvt.pokedex.core.service.application.model;

import java.util.Map;

public record UserIndicatorApplyViewModel(
        String viewName,
        Map<String, Object> attributes
) {
}
