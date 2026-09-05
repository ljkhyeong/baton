package com.personal.baton.application.workspace;

import com.personal.baton.application.identity.error.AccountNotFoundException;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase;
import com.personal.baton.application.workspace.port.out.NotificationPreferencesRepository;
import com.personal.baton.domain.workspace.NotificationPreferences;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class NotificationPreferencesService implements NotificationPreferencesUseCase {
    private final IdentityRepository identities;
    private final NotificationPreferencesRepository preferences;
    public NotificationPreferencesService(IdentityRepository identities, NotificationPreferencesRepository preferences) {
        this.identities = identities; this.preferences = preferences;
    }
    @Override public PreferencesResult get(UUID accountId) {
        identities.findAccountById(accountId).orElseThrow(AccountNotFoundException::new);
        return result(preferences.find(accountId).orElseGet(() -> NotificationPreferences.defaults(accountId)));
    }
    @Override @Transactional
    public PreferencesResult configure(UUID accountId, ConfigurePreferencesCommand command) {
        identities.findAccountByIdForUpdate(accountId).orElseThrow(AccountNotFoundException::new);
        var value = preferences.find(accountId).orElseGet(() -> NotificationPreferences.defaults(accountId));
        long version = value.getVersion() == null ? -1 : value.getVersion();
        if (version != command.expectedVersion()) throw new WorkspaceContentConflictException();
        value.configure(command.deadlineSoonEnabled(), command.overdueEnabled(), command.handoffEnabled(), command.deadlineLeadHours());
        return result(preferences.save(value));
    }
    private PreferencesResult result(NotificationPreferences value) {
        return new PreferencesResult(value.getAccountId(), value.getVersion() == null ? -1 : value.getVersion(),
                value.isDeadlineSoonEnabled(), value.isOverdueEnabled(), value.isHandoffEnabled(), value.getDeadlineLeadHours());
    }
}
