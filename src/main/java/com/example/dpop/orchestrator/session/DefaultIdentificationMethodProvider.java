package com.example.dpop.orchestrator.session;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultIdentificationMethodProvider implements IdentificationMethodProvider {

    @Override
    public List<String> availableMethods() {
        return List.of("fsc");
    }
}
