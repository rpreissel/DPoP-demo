package com.example.dpop.kcext;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review 2026-09, P-3: a federated user IS the orchestrator account. Its id - the token's sub - comes
 * from the account id, never from the address; what the account owns is read-only in Keycloak.
 */
class OrchestratorUserTest {

    private final ComponentModel component = componentWithId("orch-accounts");
    private final KcAccount account = new KcAccount(42, "max@example.com", "max@example.com", true, "Max", "Muster",
            Map.of("orchestratorAccountId", "42", "person_id", "P000000001"));
    private final OrchestratorUser user = new OrchestratorUser(null, null, component, account);

    @Test
    void theIdIsTheAccountIdNotTheAddress() {
        assertEquals("f:orch-accounts:42", user.getId());
    }

    @Test
    void namesAddressAndAttributesComeFromTheAccount() {
        assertEquals("max@example.com", user.getUsername());
        assertEquals("max@example.com", user.getEmail());
        assertEquals("Max", user.getFirstName());
        assertEquals("Muster", user.getLastName());
        assertEquals("42", user.getFirstAttribute("orchestratorAccountId"));
        assertEquals("P000000001", user.getFirstAttribute("person_id"));
        assertEquals(true, user.isEmailVerified());
    }

    @Test
    void whatTheAccountOwnsCannotBeChangedThroughKeycloak() {
        assertThrows(ReadOnlyException.class, () -> user.setEmail("other@example.com"));
        assertThrows(ReadOnlyException.class, () -> user.setUsername("other"));
        assertThrows(ReadOnlyException.class, () -> user.setFirstName("Moritz"));
        assertThrows(ReadOnlyException.class, () -> user.setSingleAttribute("orchestratorAccountId", "43"));
        assertThrows(ReadOnlyException.class, () -> user.setEmailVerified(false));
    }

    @Test
    void anAccountWithoutAddressHasNoEmail() {
        OrchestratorUser noMail = new OrchestratorUser(null, null, component,
                new KcAccount(7, "account-7", null, false, "Unbekannt", "(nicht identifiziert)", Map.of()));
        assertEquals(null, noMail.getEmail());
        assertEquals("account-7", noMail.getUsername());
        assertEquals(UserModel.USERNAME, "username");
    }

    private static ComponentModel componentWithId(String id) {
        ComponentModel model = new ComponentModel();
        model.setId(id);
        return model;
    }
}
