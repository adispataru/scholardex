package ro.uvt.pokedex.core.service.reporting.transfer.render;

import java.util.List;
import java.util.Map;

public record TileData(Map<String, Object> header, List<Map<String, Object>> innerRows) {
}
