package ro.uvt.pokedex.core.service.reporting.transfer.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingBlock;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingColumn;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingPolicy;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingScalarCell;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingSummaryFormula;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads a {@link TemplateBinding} from a classpath JSON resource and validates it against the
 * referenced workbook. Fail-fast: any structural problem in the binding or any reference to a
 * missing sheet aborts the load.
 */
@Component
public class TemplateBindingLoader {

    private static final Pattern COLUMN_LETTERS = Pattern.compile("[A-Z]+");
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final ObjectMapper objectMapper;

    public TemplateBindingLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TemplateBinding load(String bindingResourcePath) {
        TemplateBinding binding = readJson(bindingResourcePath);
        validate(binding, bindingResourcePath);
        return binding;
    }

    private TemplateBinding readJson(String bindingResourcePath) {
        Resource res = resolveResource(bindingResourcePath);
        try (InputStream in = res.getInputStream()) {
            return objectMapper.readValue(in, TemplateBinding.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read binding JSON at " + bindingResourcePath, e);
        }
    }

    private void validate(TemplateBinding binding, String bindingResourcePath) {
        if (isBlank(binding.getReportTypeKey())) {
            throw new IllegalStateException(bindingResourcePath + ": reportTypeKey is required");
        }
        if (isBlank(binding.getTemplateResource())) {
            throw new IllegalStateException(bindingResourcePath + ": templateResource is required");
        }

        Resource template = resolveResource(binding.getTemplateResource());
        try (InputStream in = template.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {
            validateScalarCells(binding, workbook, bindingResourcePath);
            validateRoles(binding, workbook, bindingResourcePath);
        } catch (IOException e) {
            throw new IllegalStateException(bindingResourcePath + ": failed to open template "
                    + binding.getTemplateResource(), e);
        }
    }

    private void validateScalarCells(TemplateBinding binding, Workbook workbook, String src) {
        for (BindingScalarCell cell : binding.getScalarCells()) {
            if (isBlank(cell.getCell())) {
                throw new IllegalStateException(src + ": scalarCells entry missing 'cell'");
            }
            CellReference ref = new CellReference(cell.getCell());
            String sheetName = ref.getSheetName();
            if (isBlank(sheetName)) {
                throw new IllegalStateException(src + ": scalar cell '" + cell.getCell()
                        + "' must be sheet-qualified (e.g. 'Sheet!A1')");
            }
            if (workbook.getSheet(sheetName) == null) {
                throw new IllegalStateException(src + ": scalar cell references missing sheet '"
                        + sheetName + "'");
            }
        }
    }

    private void validateRoles(TemplateBinding binding, Workbook workbook, String src) {
        Set<String> seen = new HashSet<>();
        for (BindingRole role : binding.getRoles()) {
            if (isBlank(role.getRoleKey())) {
                throw new IllegalStateException(src + ": role missing roleKey");
            }
            if (!seen.add(role.getRoleKey())) {
                throw new IllegalStateException(src + ": duplicate roleKey '" + role.getRoleKey() + "'");
            }
            if (role.getKind() == null) {
                throw new IllegalStateException(src + ": role '" + role.getRoleKey() + "' missing kind");
            }
            if (role.getKind() == BindingKind.FIXED_TABLE) {
                validateFixedTable(role, workbook, src);
            } else if (role.getKind() == BindingKind.STACKED_BLOCKS) {
                validateStackedBlocks(role, workbook, src);
            } else if (role.getKind() == BindingKind.TILED_SHEETS) {
                validateTiledSheets(role, workbook, src);
            }
        }
    }

    private void validateTiledSheets(BindingRole role, Workbook workbook, String src) {
        String roleId = src + ": role '" + role.getRoleKey() + "'";
        if (isBlank(role.getTemplateSheet()) || workbook.getSheet(role.getTemplateSheet()) == null) {
            throw new IllegalStateException(roleId + " missing or unknown templateSheet '"
                    + role.getTemplateSheet() + "'");
        }
        if (isBlank(role.getSummarySheet()) || workbook.getSheet(role.getSummarySheet()) == null) {
            throw new IllegalStateException(roleId + " missing or unknown summarySheet '"
                    + role.getSummarySheet() + "'");
        }
        if (isBlank(role.getPerTileTitleCell())) {
            throw new IllegalStateException(roleId + " missing perTileTitleCell");
        }
        if (isBlank(role.getSheetNameTemplate())) {
            throw new IllegalStateException(roleId + " missing sheetNameTemplate");
        }
        if (role.getInnerTableFirstDataRow() == null || role.getInnerTableFirstDataRow() < 1) {
            throw new IllegalStateException(roleId + " innerTableFirstDataRow must be >= 1");
        }
        if (role.getInnerTableMaxRows() == null || role.getInnerTableMaxRows() < 1) {
            throw new IllegalStateException(roleId + " innerTableMaxRows must be >= 1");
        }
        validateColumnMap(role.getInnerColumns(), roleId + " innerColumns");
        for (BindingSummaryFormula sf : role.getSummaryFormulas()) {
            if (isBlank(sf.getCell()) || isBlank(sf.getTileCell()) || sf.getRule() == null) {
                throw new IllegalStateException(roleId + " summaryFormulas entry incomplete");
            }
        }
    }

    private void validateStackedBlocks(BindingRole role, Workbook workbook, String src) {
        String roleId = src + ": role '" + role.getRoleKey() + "'";
        if (isBlank(role.getSheet())) {
            throw new IllegalStateException(roleId + " missing sheet");
        }
        if (workbook.getSheet(role.getSheet()) == null) {
            throw new IllegalStateException(roleId + " references missing sheet '" + role.getSheet() + "'");
        }
        if (isBlank(role.getGroupingKey())) {
            throw new IllegalStateException(roleId + " missing groupingKey");
        }
        if (role.getBlocks() == null || role.getBlocks().isEmpty()) {
            throw new IllegalStateException(roleId + " must declare at least one block");
        }
        for (int i = 0; i < role.getBlocks().size(); i++) {
            BindingBlock b = role.getBlocks().get(i);
            String blockId = roleId + " block[" + i + "]";
            if (isBlank(b.getActivityName())) {
                throw new IllegalStateException(blockId + " missing activityName");
            }
            if (b.getFirstDataRow() == null || b.getFirstDataRow() < 1) {
                throw new IllegalStateException(blockId + " firstDataRow must be >= 1");
            }
            if (b.getTotalRow() == null || b.getTotalRow() < 1) {
                throw new IllegalStateException(blockId + " totalRow must be >= 1");
            }
        }
        validateColumnMap(role.getBlockColumns(), roleId + " blockColumns");
        validateColumnMap(role.getTotalRowColumns(), roleId + " totalRowColumns");
    }

    private void validateColumnMap(Map<String, BindingColumn> columns, String mapId) {
        for (Map.Entry<String, BindingColumn> e : columns.entrySet()) {
            String letter = e.getKey();
            if (!COLUMN_LETTERS.matcher(letter).matches()) {
                throw new IllegalStateException(mapId + " column key '" + letter
                        + "' is not a valid Excel column reference");
            }
            BindingColumn col = e.getValue();
            if (col == null || col.getPolicy() == null) {
                throw new IllegalStateException(mapId + " column '" + letter + "' missing policy");
            }
            if ((col.getPolicy() == BindingPolicy.WRITE || col.getPolicy() == BindingPolicy.WRITE_SCORE)
                    && isBlank(col.getSource())) {
                throw new IllegalStateException(mapId + " column '" + letter
                        + "' with policy " + col.getPolicy() + " requires a source");
            }
        }
    }

    private void validateFixedTable(BindingRole role, Workbook workbook, String src) {
        String roleId = src + ": role '" + role.getRoleKey() + "'";
        if (isBlank(role.getSheet())) {
            throw new IllegalStateException(roleId + " missing sheet");
        }
        Sheet sheet = workbook.getSheet(role.getSheet());
        if (sheet == null) {
            throw new IllegalStateException(roleId + " references missing sheet '" + role.getSheet() + "'");
        }
        if (role.getFirstDataRow() == null || role.getFirstDataRow() < 1) {
            throw new IllegalStateException(roleId + " firstDataRow must be >= 1");
        }
        if (role.getMaxRows() == null || role.getMaxRows() < 1) {
            throw new IllegalStateException(roleId + " maxRows must be >= 1");
        }
        validateColumnMap(role.getColumns(), roleId + " columns");
    }

    private Resource resolveResource(String path) {
        String stripped = path.startsWith(CLASSPATH_PREFIX) ? path.substring(CLASSPATH_PREFIX.length()) : path;
        return new ClassPathResource(stripped);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
