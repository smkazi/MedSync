package com.hms.notification.repo;

import com.hms.notification.domain.MessageTemplate;
import com.hms.notification.domain.NotificationEnums;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, UUID> {

    Optional<MessageTemplate> findByCategoryAndChannel(NotificationEnums.Category category,
                                                       NotificationEnums.Channel channel);

    List<MessageTemplate> findAllByOrderByCategoryAscChannelAsc();
}
