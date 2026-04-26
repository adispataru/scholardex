package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

public record FilterFieldDef(
        String name,
        String label,
        String type,
        String value,
        List<FilterOptionDef> options
) {
    public FilterFieldDef {
        type = type == null || type.isBlank() ? "text" : type;
        value = value == null ? "" : value;
        options = options == null ? List.of() : List.copyOf(options);
    }

    public FilterFieldDef(String name, String label, String type, String value) {
        this(name, label, type, value, List.of());
    }
}
