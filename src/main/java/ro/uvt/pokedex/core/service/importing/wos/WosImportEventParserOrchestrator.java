package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunSummary;

import java.util.ArrayList;
import java.util.List;

@Service
public class WosImportEventParserOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(WosImportEventParserOrchestrator.class);
    private static final int PARSER_HEARTBEAT_INTERVAL = 10_000;
    private static final Sort EVENT_SORT = Sort.by(
            Sort.Order.asc("sourceType"),
            Sort.Order.asc("sourceFile"),
            Sort.Order.asc("sourceVersion"),
            Sort.Order.asc("sourceRowItem")
    );

    private final WosImportEventRepository importEventRepository;
    private final List<WosImportEventParser> parsers;

    public WosImportEventParserOrchestrator(
            WosImportEventRepository importEventRepository,
            List<WosImportEventParser> parsers
    ) {
        this.importEventRepository = importEventRepository;
        this.parsers = parsers;
    }

    public WosParserRunResult parseAllEvents() {
        List<WosImportEvent> events = new ArrayList<>(importEventRepository.findAll(EVENT_SORT));
        return parseEvents(events);
    }

    public WosParserRunResult parseEvents(List<WosImportEvent> events) {
        WosParserRunSummary summary = new WosParserRunSummary(20);
        List<WosParsedRecord> records = new ArrayList<>();
        for (WosImportEvent event : events) {
            summary.markProcessed();
            if (summary.getProcessedCount() % PARSER_HEARTBEAT_INTERVAL == 0) {
                log.info("WoS parser progress: processed={} parsed={} skipped={} errors={}",
                        summary.getProcessedCount(), summary.getParsedCount(),
                        summary.getSkippedCount(), summary.getErrorCount());
            }
            WosParsedEventResult result = parseEvent(event);
            if (result.status() == WosParsedEventStatus.PARSED) {
                summary.markParsed();
                records.addAll(result.records());
                continue;
            }
            if (result.status() == WosParsedEventStatus.SKIPPED) {
                summary.markSkipped(sample(event, result.message()));
                continue;
            }
            summary.markError(sample(event, result.message()));
        }
        log.info("WoS parser summary: processed={} parsed={} skipped={} errors={} sample={}",
                summary.getProcessedCount(), summary.getParsedCount(), summary.getSkippedCount(),
                summary.getErrorCount(), summary.getSamples());
        return new WosParserRunResult(summary, records);
    }

    public WosParsedEventResult parseEvent(WosImportEvent event) {
        for (WosImportEventParser parser : parsers) {
            if (parser.supports(event)) {
                return parser.parse(event);
            }
        }
        return WosParsedEventResult.skipped("no parser for source/payload");
    }

    private String sample(WosImportEvent event, String message) {
        if (event == null) {
            return "null event: " + message;
        }
        return event.getSourceType() + ":" + event.getSourceFile() + "#" + event.getSourceRowItem() + " " + message;
    }
}
