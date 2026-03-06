package ro.uvt.pokedex.core.service.importing.wos.model;

import java.util.List;

public record WosParserRunResult(
        WosParserRunSummary summary,
        List<WosParsedRecord> records
) {
}
