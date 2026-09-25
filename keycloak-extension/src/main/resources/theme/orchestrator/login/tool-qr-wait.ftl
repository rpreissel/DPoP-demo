<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>

        <#if step == "enterCode">
            <#-- The app approved and shows a confirmation code; only typing it here logs this browser
                 in (review 2026-09, M-2, docs/07-betrieb.md #5). No polling on this step. -->
            <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                <div class="${properties.kcFormGroupClass!}">
                    <label for="confirmationCode" class="${properties.kcLabelClass!}">${t.of("Code aus der App")}</label>
                    <input type="text" id="confirmationCode" name="confirmationCode" class="${properties.kcInputClass!}"
                           inputmode="numeric" autocomplete="one-time-code" autofocus/>
                    <span class="orchestrator-hint">${t.of("Ihre App zeigt nach der Freigabe einen sechsstelligen Code. Geben Sie ihn hier ein.")}</span>
                </div>
                <div class="orchestrator-actions">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                            type="submit" name="orchestrator_abandon" value="true">${t.of("Abbrechen")}</button>
                </div>
            </form>
        <#else>
        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <img src="${qrDataUri}" alt="${t.of("QR-Code")}" width="220" height="220"/>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <#-- Manuelle Eingabe ist ein gleichwertiger Weg, kein Fallback (docs/journeys/
                 confirm-peer-login.md) - der Code muss deshalb hier auch gut lesbar/abschreibbar stehen,
                 nicht nur im QR-Bild bzw. versteckt in der Demo-Link-URL. -->
            <p>${t.of("Pairing-Code")}: <strong class="orchestrator-qr-code">${pairingCode}</strong></p>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <p class="orchestrator-hint">
                ${t.of("Nach der Freigabe zeigt Ihre App einen Code, den Sie hier eingeben.")}
            </p>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <#-- Named target (not the default same-tab navigation): a click must not navigate this
                 waiting WEB screen away. Same window name as Willkommen's own App-Kanal link
                 (docs/10-frontend.md #0) - reuses an already-open App-Kanal tab when clicked from
                 there, but NOT from here: this page's origin (this Keycloak host) differs from the
                 App-Kanal's, and Chrome does not resolve named targets across origins even when both
                 tabs share a common opener - each click opens a fresh tab (browsergetestet,
                 docs/10-frontend.md #0). Harmless: intent=confirm_peer_login still lands correctly
                 in whichever tab opens. -->
            <a href="${deepLink}" target="dpop-demo-app-kanal">${deepLink}</a>
            <p class="orchestrator-hint">
                ${t.of("Demo-Link: öffnet die App direkt (ohne Kamera) mit vorbefülltem Pairing-Code.")}
            </p>
        </div>

        <form id="kc-orchestrator-tool-form" action="${url.loginAction}" method="post">
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Abbrechen")}</button>
            </div>
        </form>

        <#-- Re-submits the (empty) form periodically to poll for the app's decision - safe to do
             at this rate: an InProgress outcome never charges any attempt/login counter.
             Stops once the page navigates away. -->
        <script>
            setTimeout(function () {
                document.getElementById("kc-orchestrator-tool-form").submit();
            }, 3000);
        </script>
        </#if>
    </#if>
</@layout.registrationLayout>
