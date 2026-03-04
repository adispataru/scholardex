package ro.uvt.pokedex.core.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.controller.dto.AdminResearcherUpsertRequest;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.service.ResearcherService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/researchers")
public class AdminResearcherController {

    private final ResearcherService researcherService;

    @Autowired
    public AdminResearcherController(ResearcherService researcherService) {
        this.researcherService = researcherService;
    }

    @PostMapping
    public ResponseEntity<Researcher> addResearcher(@Valid @RequestBody AdminResearcherUpsertRequest request) {
        Researcher researcher = mapRequestToResearcher(request);
        Researcher savedResearcher = researcherService.saveResearcher(researcher);
        return ResponseEntity.ok(savedResearcher);
    }

    @GetMapping
    public ResponseEntity<List<Researcher>> getAllResearchers() {
        List<Researcher> researchers = researcherService.findAllResearchers();
        return ResponseEntity.ok(researchers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Researcher> getResearcherById(@PathVariable String id) {
        return researcherService.findResearcherById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Researcher> updateResearcher(@PathVariable String id, @Valid @RequestBody AdminResearcherUpsertRequest request) {
        Researcher researcher = mapRequestToResearcher(request);
        Researcher updatedResearcher = researcherService.updateResearcher(id, researcher);
        return ResponseEntity.ok(updatedResearcher);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResearcher(@PathVariable String id) {
        researcherService.deleteResearcher(id);
        return ResponseEntity.ok().build();
    }

    private Researcher mapRequestToResearcher(AdminResearcherUpsertRequest request) {
        Researcher researcher = new Researcher();
        researcher.setFirstName(request.firstName());
        researcher.setLastName(request.lastName());
        researcher.setScholarId(request.scholarId());
        researcher.setScopusId(request.normalizedScopusId());
        researcher.setWosId(request.normalizedWosId());
        researcher.setPosition(request.position());
        return researcher;
    }
}
