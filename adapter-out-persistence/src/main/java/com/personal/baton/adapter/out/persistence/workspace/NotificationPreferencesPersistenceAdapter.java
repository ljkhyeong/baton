package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.port.out.NotificationPreferencesRepository;
import com.personal.baton.domain.workspace.NotificationPreferences;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationPreferencesPersistenceAdapter implements NotificationPreferencesRepository {
    private final NotificationPreferencesJpaRepository repository;
    public NotificationPreferencesPersistenceAdapter(NotificationPreferencesJpaRepository repository) { this.repository = repository; }
    @Override public Optional<NotificationPreferences> find(UUID accountId) { return repository.findById(accountId); }
    @Override public NotificationPreferences save(NotificationPreferences value) { return repository.saveAndFlush(value); }
}
