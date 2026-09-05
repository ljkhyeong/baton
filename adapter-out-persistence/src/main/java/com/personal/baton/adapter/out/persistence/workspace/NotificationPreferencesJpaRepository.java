package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.NotificationPreferences;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferencesJpaRepository extends JpaRepository<NotificationPreferences, UUID> {}
