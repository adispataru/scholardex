package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import ro.uvt.pokedex.core.service.application.ActivityUnitImportFacade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * H142 slice 2 — a head imports the filled fișe of the unit's members at once (each colleague importing their own
 * stays the normal flow). The access rule of each unit kind applies, as on the unit's CNFIS page. The results are
 * shown on the page that answers the upload; importing a file again adds nothing.
 */
@Controller
@RequestMapping("/supervisor/{kind:departments|divisions}/{unitId}/activity-import")
@RequiredArgsConstructor
public class ActivityUnitImportController {

    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_FILES = 100;

    private final ActivityUnitImportFacade facade;

    @GetMapping
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public String show(@PathVariable String kind, @PathVariable String unitId, Model model) {
        Optional<ActivityUnitImportFacade.UnitPage> page = facade.page(kind, unitId);
        if (page.isEmpty()) {
            return "redirect:/supervisor";
        }
        model.addAttribute("unit", page.get());
        return "supervisor/activity-import";
    }

    @PostMapping
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public String upload(@PathVariable String kind, @PathVariable String unitId,
                         @RequestParam(name = "files", required = false) List<MultipartFile> files,
                         @RequestParam(name = "member", required = false) String member,
                         Authentication authentication, Model model) throws IOException {
        Optional<ActivityUnitImportFacade.UnitPage> page = facade.page(kind, unitId);
        if (page.isEmpty()) {
            return "redirect:/supervisor";
        }
        model.addAttribute("unit", page.get());
        List<ActivityUnitImportFacade.UploadedFile> uploaded = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            if (file == null || file.isEmpty()) continue;
            String name = file.getOriginalFilename() == null ? "fișier" : file.getOriginalFilename();
            if (file.getSize() > MAX_FILE_BYTES || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                    || uploaded.size() >= MAX_FILES) {
                refused.add(name);
                continue;
            }
            uploaded.add(new ActivityUnitImportFacade.UploadedFile(name, file.getBytes()));
        }
        model.addAttribute("results", facade.importFiles(kind, unitId, uploaded, member, authentication.getName()));
        model.addAttribute("refusedFiles", refused);
        return "supervisor/activity-import";
    }
}
