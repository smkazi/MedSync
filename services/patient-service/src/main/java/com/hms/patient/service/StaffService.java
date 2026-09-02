package com.hms.patient.service;

import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.patient.domain.Department;
import com.hms.patient.domain.Staff;
import com.hms.patient.repo.DepartmentRepository;
import com.hms.patient.repo.StaffRepository;
import com.hms.patient.web.dto.PatientDtos;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Clinical staff and department administration. */
@Service
public class StaffService {

    private final StaffRepository staff;
    private final DepartmentRepository departments;
    private final AuditService audit;

    public StaffService(StaffRepository staff, DepartmentRepository departments, AuditService audit) {
        this.staff = staff;
        this.departments = departments;
        this.audit = audit;
    }

    @Transactional
    public PatientDtos.StaffResponse create(PatientDtos.CreateStaffRequest request) {
        if (staff.existsByEmployeeNo(request.employeeNo())) {
            throw new ConflictException("Employee number '" + request.employeeNo() + "' is already in use");
        }
        if (request.userId() != null && staff.findByUserId(request.userId()).isPresent()) {
            throw new ConflictException("That login is already linked to another staff record");
        }
        Staff member = new Staff(request.employeeNo().trim(), request.fullName().trim(), request.designation());
        member.setUserId(request.userId());
        member.setDepartment(resolveDepartment(request.departmentCode()));
        member.setSpecialty(request.specialty());
        member.setLicenseNo(request.licenseNo());
        member.setPhone(request.phone());
        member.setEmail(request.email());
        staff.save(member);

        audit.record("STAFF_CREATED", "Staff", member.getId(), member.getEmployeeNo() + " " + member.getDesignation());
        return PatientMapper.toResponse(member);
    }

    @Transactional(readOnly = true)
    public Page<PatientDtos.StaffResponse> search(String query, String departmentCode, boolean includeInactive,
                                                 Pageable pageable) {
        return staff.search(QueryPatterns.contains(query), QueryPatterns.exactOrAny(departmentCode),
                includeInactive, pageable).map(PatientMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PatientDtos.StaffResponse getById(UUID id) {
        return PatientMapper.toResponse(require(id));
    }

    @Transactional(readOnly = true)
    public Staff require(UUID id) {
        return staff.findDetailById(id).orElseThrow(() -> NotFoundException.of("Staff", id));
    }

    @Transactional
    public PatientDtos.StaffResponse update(UUID id, PatientDtos.UpdateStaffRequest request) {
        Staff member = require(id);
        if (request.fullName() != null) {
            member.setFullName(request.fullName().trim());
        }
        if (request.designation() != null) {
            member.setDesignation(request.designation());
        }
        if (request.userId() != null) {
            staff.findByUserId(request.userId())
                    // id on the left: an entity id is nullable until persisted.
                    .filter(existing -> !id.equals(existing.getId()))
                    .ifPresent(existing -> {
                        throw new ConflictException("That login is already linked to another staff record");
                    });
            member.setUserId(request.userId());
        }
        if (request.departmentCode() != null) {
            member.setDepartment(resolveDepartment(request.departmentCode()));
        }
        if (request.specialty() != null) {
            member.setSpecialty(request.specialty());
        }
        if (request.licenseNo() != null) {
            member.setLicenseNo(request.licenseNo());
        }
        if (request.phone() != null) {
            member.setPhone(request.phone());
        }
        if (request.email() != null) {
            member.setEmail(request.email());
        }
        if (request.active() != null) {
            member.setActive(request.active());
        }
        audit.record("STAFF_UPDATED", "Staff", id, "active=" + member.isActive());
        return PatientMapper.toResponse(member);
    }

    @Transactional(readOnly = true)
    public List<PatientDtos.DepartmentResponse> listDepartments(boolean includeInactive) {
        List<Department> found = includeInactive ? departments.findAll() : departments.findByActiveTrueOrderByName();
        return found.stream().map(PatientMapper::toResponse).toList();
    }

    @Transactional
    public PatientDtos.DepartmentResponse createDepartment(PatientDtos.CreateDepartmentRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (departments.existsByCode(code)) {
            throw new ConflictException("Department code '" + code + "' already exists");
        }
        Department department = new Department(code, request.name().trim(), request.description());
        departments.save(department);
        audit.record("DEPARTMENT_CREATED", "Department", department.getId(), code);
        return PatientMapper.toResponse(department);
    }

    /**
     * Corrects or retires a department.
     *
     * <p>It could be created and never touched again, which for a vocabulary every other service
     * references is a gap rather than a simplification: a department opened under the wrong name,
     * or closed when a ward closed, had no path but SQL. Retiring one sets {@code active} false and
     * keeps every row that points at it, because the encounters recorded under it are still real.
     */
    @Transactional
    public PatientDtos.DepartmentResponse updateDepartment(String code,
                                                           PatientDtos.UpdateDepartmentRequest request) {
        String normalised = code.trim().toUpperCase(Locale.ROOT);
        Department department = departments.findByCode(normalised)
                .orElseThrow(() -> new NotFoundException("Department '" + normalised + "' was not found"));
        if (request.name() != null) {
            department.setName(request.name().trim());
        }
        if (request.description() != null) {
            department.setDescription(request.description());
        }
        if (request.active() != null) {
            department.setActive(request.active());
        }
        audit.record("DEPARTMENT_UPDATED", "Department", department.getId(),
                normalised + " active=" + department.isActive());
        return PatientMapper.toResponse(departments.save(department));
    }

    private Department resolveDepartment(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return departments.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new BadRequestException("Unknown department code '" + code + "'"));
    }
}
