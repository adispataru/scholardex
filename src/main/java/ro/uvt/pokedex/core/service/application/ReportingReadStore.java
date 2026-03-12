package ro.uvt.pokedex.core.service.application;

import java.util.Locale;

public enum ReportingReadStore {
    MONGO,
    POSTGRES;

    public static ReportingReadStore fromProperty(String raw) {
        if (raw == null || raw.isBlank()) {
            return MONGO;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MONGO" -> MONGO;
            case "POSTGRES" -> POSTGRES;
            default -> throw new IllegalArgumentException("Invalid reporting read-store value: " + raw + ". Allowed: mongo, postgres.");
        };
    }
}
