package za.co.zccbooks.registry;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static za.co.zccbooks.registry.RegistryDtos.*;

@RestController
@RequestMapping("/api")
public class RegistryController {
    private final RegistryService service;

    public RegistryController(RegistryService service) {
        this.service = service;
    }

    @GetMapping("/organisations")
    public List<OrganisationResponse> organisations() {
        return service.organisations();
    }

    @PostMapping("/organisations")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganisationResponse createOrganisation(
            @Valid @RequestBody OrganisationRequest request) {
        return service.createOrganisation(request);
    }

    @GetMapping("/organisations/{organisationId}/branches")
    public List<BranchResponse> branches(@PathVariable UUID organisationId) {
        return service.branches(organisationId);
    }

    @PostMapping("/organisations/{organisationId}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponse createBranch(@PathVariable UUID organisationId,
            @Valid @RequestBody BranchRequest request) {
        return service.createBranch(organisationId, request);
    }

    @GetMapping("/organisations/{organisationId}/classifications")
    public List<ClassificationResponse> classifications(@PathVariable UUID organisationId) {
        return service.classifications(organisationId);
    }

    @PostMapping("/organisations/{organisationId}/classifications")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassificationResponse createClassification(@PathVariable UUID organisationId,
            @Valid @RequestBody ClassificationRequest request) {
        return service.createClassification(organisationId, request);
    }

    @GetMapping("/branches/{branchId}/members")
    public List<MemberResponse> members(@PathVariable UUID branchId,
            @RequestParam(required = false) String search) {
        return service.members(branchId, search);
    }

    @PostMapping("/branches/{branchId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse createMember(@PathVariable UUID branchId,
            @Valid @RequestBody MemberRequest request) {
        return service.createMember(branchId, request);
    }
}