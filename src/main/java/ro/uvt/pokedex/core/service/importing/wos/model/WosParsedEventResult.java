package ro.uvt.pokedex.core.service.importing.wos.model;

import java.util.List;

public record WosParsedEventResult(
        WosParsedEventStatus status,
        List<WosParsedRecord> records,
        String message
) {
    public static WosParsedEventResult parsed(List<WosParsedRecord> records) {
        return new WosParsedEventResult(WosParsedEventStatus.PARSED, records, null);
    }

    public static WosParsedEventResult skipped(String message) {
        return new WosParsedEventResult(WosParsedEventStatus.SKIPPED, List.of(), message);
    }

    public static WosParsedEventResult error(String message) {
        return new WosParsedEventResult(WosParsedEventStatus.ERROR, List.of(), message);
    }
}
