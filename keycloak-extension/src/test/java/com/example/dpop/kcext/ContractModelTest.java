package com.example.dpop.kcext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.dpop.kcext.api.model.ChannelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Die generierten Vertragsmodelle gegen echtes Antwort-JSON.
 *
 * Bis hierher las OrchestratorClient den Vertrag von Hand ({@code path("channel").asText(null)}).
 * Das ist der schlechteste Fehlermodus: Ein umbenanntes Feld liefert {@code null} statt eines
 * Fehlers. Dieser Test sichert, dass die erzeugten Modelle die Antwort wirklich tragen - und
 * benennt die eine Stelle, an der sie es NICHT tun.
 */
class ContractModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialisiertEineVollstaendigeAntwort() throws Exception {
        String json = """
            {
              "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                          "channelType": "KEYCLOAK", "state": "STEP_UP_IN_PROGRESS"},
              "next": {"type": "tool", "toolId": "auth-sms", "step": "auth"},
              "authData": {"accountId": 42, "acr": "loa2", "amr": {"sms": "orchestrator"}}
            }
            """;
        ChannelResponse response = mapper.readValue(json, ChannelResponse.class);

        assertEquals("STEP_UP_IN_PROGRESS", response.getChannel().getState());
        assertEquals("auth-sms", response.getNext().getToolId());
        assertEquals(42L, response.getAuthData().getAccountId());
    }

    /**
     * Der Punkt, an dem der generierte Client NICHT vorwaertskompatibel ist.
     *
     * stepData ist eine Union aus allen heute bekannten Formen. Liefert ein neuerer Orchestrator
     * eine Form, die dieser Client noch nicht kennt, probiert der erzeugte Deserialisierer alle
     * Untertypen durch und wirft, wenn keiner passt. Fuer eine Extension, die in einem eigenen
     * Container-Image ausgeliefert wird und einem Deploy-Versatz standhalten muss, ist das ein
     * echter Bruch - kein ignoriertes Feld.
     *
     * Deshalb liest OrchestratorClient stepData weiterhin als offenen JsonNode. Dieser Test haelt
     * den Grund fest: Er schlaegt fehl, sobald die Union das Problem nicht mehr hat.
     */
    @Test
    void unbekannteStepDataFormBrichtDieUnion() {
        String vonMorgen = """
            {"channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                         "channelType": "KEYCLOAK", "state": "STEP_UP_IN_PROGRESS"},
             "stepData": {"kind": "eine-form-von-morgen", "irgendwas": 1}}
            """;
        assertThrows(Exception.class, () -> mapper.readValue(vonMorgen, ChannelResponse.class));

        // Als offener Knoten gelesen ueberlebt dieselbe Antwort.
        assertNotNull(assertDoesNotThrowJson(vonMorgen).path("stepData").path("kind").asText(null));
    }

    /**
     * Der Weg, den der Client wirklich geht: Huelle getypt, die beiden offenen Beutel als
     * JsonNode. Deshalb traegt diese Antwort beides gleichzeitig - eine stepData-Form, die dieser
     * Client nicht kennt, UND ein Feld in der Huelle, das es heute noch nicht gibt.
     */
    @Test
    void ueberstehtEineAntwortVonMorgen() {
        String vonMorgen = """
            {
              "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                          "channelType": "KEYCLOAK", "state": "STEP_UP_IN_PROGRESS",
                          "einNeuesFeld": "spaeter dazugekommen"},
              "next": {"type": "tool", "toolId": "auth-sms", "step": "auth",
                       "toolSessionId": "7d2b1d7e-0000-4000-8000-000000000001"},
              "stepData": {"kind": "eine-form-von-morgen", "prompt": "Bitte bestaetigen"},
              "demo": {"tan": "123456"},
              "authData": {"accountId": 42, "acr": "loa2", "amr": {"sms": "orchestrator"}}
            }
            """;
        OrchestratorClient.ChannelResponse flach = OrchestratorClient.ChannelResponse.from(assertDoesNotThrowJson(vonMorgen));

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", flach.channelSessionId());
        assertEquals("STEP_UP_IN_PROGRESS", flach.channelState());
        assertEquals("auth-sms", flach.next().toolId());
        assertEquals("7d2b1d7e-0000-4000-8000-000000000001", flach.next().toolSessionId());
        assertEquals(42L, flach.authDataAccountId());
        assertEquals("orchestrator", flach.authDataAmr().get("sms"));
        // Die beiden Beutel kommen unveraendert durch, auch in einer Form von morgen.
        assertEquals("Bitte bestaetigen", flach.stepData().get("prompt").asText());
        assertEquals("123456", flach.demo().get("tan").asText());
    }

    private JsonNode assertDoesNotThrowJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("readTree sollte jede Antwort lesen koennen", e);
        }
    }
}
