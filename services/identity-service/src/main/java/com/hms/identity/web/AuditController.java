package com.hms.identity.web;

import com.hms.common.api.PageResponse;
import com.hms.common.data.QueryPatterns;
import com.hms.common.security.Roles;
import com.hms.identity.repo.AuditLogRepository;
import com.hms.identity.service.UserMapper;
import com.hms.identity.web.dto.AuthDtos;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the platform audit trail. Admin only — it spans every service's activity. */
@RestController
@RequestMapping("/admin/audit")
@PreAuthorize(Roles.ADMIN_ONLY)
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public PageResponse<AuthDtos.AuditResponse> list(@RequestParam(required = false) String entity,
                                                     @RequestParam(required = false) String action,
                                                     @RequestParam(required = false) String actorId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(
                repository.search(QueryPatterns.exactOrAny(entity), QueryPatterns.exactOrAny(action),
                        QueryPatterns.exactOrAny(actorId), PageRequest.of(page, Math.min(size, 200))),
                UserMapper::toResponse);
    }
}
