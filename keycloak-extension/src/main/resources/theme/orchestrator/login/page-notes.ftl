<#-- The two notes every orchestrator page carries (orchestrator.css): at the top, in the white
     bar, that the visitor has left the website for Keycloak; at the bottom, in the dark band,
     which theme drew the page. The Keycloakify theme says the same in its Layout.tsx. -->
<#macro notes>
    <p class="orchestrator-note-header">${t.of("Sie sind jetzt bei Keycloak, dem Anmeldedienst dieser Website.")}</p>
    <p class="orchestrator-made-with">${t.of("Erstellt mit {technik}", {"technik": "FreeMarker"})}</p>
</#macro>
