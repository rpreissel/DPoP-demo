package com.example.dpop.orchestrator.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "step", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = NextStep.RegistrationNextStep.class, name = "registration"),
        @JsonSubTypes.Type(value = NextStep.UseIdentificationMethodNextStep.class, name = "useIdentificationMethod"),
        @JsonSubTypes.Type(value = NextStep.FscInputNextStep.class, name = "input"),
        @JsonSubTypes.Type(value = NextStep.AuthenticationSetupNextStep.class, name = "setup")
})
public sealed interface NextStep {

    String context();

    String step();

    record RegistrationNextStep(String context, String step) implements NextStep {
        public RegistrationNextStep() {
            this("registration", "registration");
        }
    }

    record UseIdentificationMethodNextStep(
            String context,
            String step,
            List<String> identificationMethods
    ) implements NextStep {
        public UseIdentificationMethodNextStep(List<String> identificationMethods) {
            this("registration", "useIdentificationMethod", identificationMethods);
        }
    }

    record FscInputNextStep(String context, String step) implements NextStep {
        public FscInputNextStep() {
            this("fsc", "input");
        }
    }

    record AuthenticationSetupNextStep(
            String context,
            String step,
            List<String> authenticationMethods
    ) implements NextStep {
        public AuthenticationSetupNextStep(List<String> authenticationMethods) {
            this("authentication", "setup", authenticationMethods);
        }
    }
}
