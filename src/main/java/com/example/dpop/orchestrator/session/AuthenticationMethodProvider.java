package com.example.dpop.orchestrator.session;

import java.util.List;

public interface AuthenticationMethodProvider {

    List<String> availableMethods();
}
