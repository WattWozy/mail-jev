package dev.jefe.mailsorter.application.port.in;


import dev.jefe.mailsorter.application.port.in.dto.AccountView;

import java.util.List;

public interface ListAccountsUseCase {
    List<AccountView> list();
    void connectSaved(String accountId);
}
