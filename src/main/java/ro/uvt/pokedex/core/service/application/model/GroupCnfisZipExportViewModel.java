package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

public record GroupCnfisZipExportViewModel(
        List<GroupMemberCnfisWorkbook> workbooks
) {
}
