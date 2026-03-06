package ro.uvt.pokedex.core.service.importing.wos;

import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;

public interface WosImportEventParser {
    boolean supports(WosImportEvent event);

    WosParsedEventResult parse(WosImportEvent event);
}
