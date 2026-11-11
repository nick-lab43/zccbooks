package za.co.zccbooks.registry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class RegistryDtos {
    private RegistryDtos() {}

    public record OrganisationRequest(
        @NotBlank @Size(max = 160) String name
    ) {}

    public record OrganisationResponse(
        UUID id, String name, OffsetDateTime createdAt
    ) {}

    public record BranchRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 160) String districtName,
        @Pattern(regexp = "^[A-Z]{2}$", message = "Country code must be two uppercase letters")
        String countryCode
    ) {}

    public record BranchResponse(
        UUID id, UUID organisationId, String code, String name,
        String districtName, String countryCode
    ) {}

    public record ClassificationRequest(
        @NotBlank @Size(max = 60) String code,
        @NotBlank @Size(max = 120) String name
    ) {}

    public record ClassificationResponse(
        UUID id, UUID organisationId, String code, String name, boolean active
    ) {}

    public record MemberRequest(
        @Size(max = 80) String zccAppNumber,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        UUID classificationId
    ) {}

    public record MemberResponse(
        UUID id, UUID branchId, String zccAppNumber, String firstName,
        String lastName, UUID classificationId, String classificationName,
        boolean active, OffsetDateTime createdAt
    ) {}
}