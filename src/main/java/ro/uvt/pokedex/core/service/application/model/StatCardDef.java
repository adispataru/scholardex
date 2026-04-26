package ro.uvt.pokedex.core.service.application.model;

public record StatCardDef(
        String label,
        Object value,
        String accent,
        String contextLine,
        String icon
) {
    public StatCardDef {
        accent = accent == null || accent.isBlank() ? "neutral" : accent;
        contextLine = contextLine == null ? "" : contextLine;
        icon = icon == null ? "" : icon;
    }

    public StatCardDef(String label, Object value, String accent, String contextLine) {
        this(label, value, accent, contextLine, "");
    }
}
