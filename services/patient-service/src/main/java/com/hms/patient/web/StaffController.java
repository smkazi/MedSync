package com.hms.patient.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.patient.service.StaffService;
import com.hms.patient.web.dto.PatientDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StaffController {

    private final StaffService service;

    public StaffController(StaffService service) {
        this.service = service;
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public PatientDtos.StaffResponse create(@Valid @RequestBody PatientDtos.CreateStaffRequest request) {
        return service.create(request);
    }

    @GetMapping("/staff")
    @PreAuthorize(Roles.CLINICAL_READ)
    public PageResponse<PatientDtos.StaffResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.search(q, department, includeInactive,
                PageRequest.of(page, Math.min(size, 100), Sort.by("fullName"))));
    }

    @GetMapping("/staff/{id}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public PatientDtos.StaffResponse get(@PathVariable UUID id) {
        return service.getById(id);
    }

    @PatchMapping("/staff/{id}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public PatientDtos.StaffResponse update(@PathVariable UUID id,
                                            @Valid @RequestBody PatientDtos.UpdateStaffRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/departments")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<PatientDtos.DepartmentResponse> departments(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.listDepartments(includeInactive);
    }

    @PatchMapping("/departments/{code}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public PatientDtos.DepartmentResponse updateDepartment(@PathVariable String code,
            @Valid @RequestBody PatientDtos.UpdateDepartmentRequest request) {
        return service.updateDepartment(code, request);
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public PatientDtos.DepartmentResponse createDepartment(
            @Valid @RequestBody PatientDtos.CreateDepartmentRequest request) {
        return service.createDepartment(request);
    }
}
