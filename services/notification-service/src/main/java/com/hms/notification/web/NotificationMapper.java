package com.hms.notification.web;

import com.hms.notification.domain.MessageTemplate;
import com.hms.notification.domain.Notification;
import com.hms.notification.web.dto.NotificationDtos;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper {

    public NotificationDtos.NotificationResponse toResponse(Notification notification) {
        return new NotificationDtos.NotificationResponse(
                notification.getId(), notification.getChannel(), notification.getCategory(),
                notification.getRecipient(), notification.getSubject(), notification.getBody(),
                notification.getStatus(), notification.getAttempts(), notification.getPatientId(),
                notification.getReference(), notification.getCreatedAt(), notification.getSentAt(),
                notification.getFailedReason());
    }

    public NotificationDtos.TemplateResponse toResponse(MessageTemplate template) {
        return new NotificationDtos.TemplateResponse(template.getId(), template.getCategory(),
                template.getChannel(), template.getSubject(), template.getBody(), template.isActive());
    }
}
