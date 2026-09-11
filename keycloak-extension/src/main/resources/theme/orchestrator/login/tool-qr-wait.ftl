<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <img src="${qrDataUri}" alt="QR-Code" width="220" height="220"/>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <#-- Manuelle Eingabe ist ein gleichwertiger Weg, kein Fallback (docs/ideen/qr-login-
                 ueber-app.md #6) - der Code muss deshalb hier auch gut lesbar/abschreibbar stehen,
                 nicht nur im QR-Bild bzw. versteckt in der Demo-Link-URL. -->
            <p>Pairing-Code: <strong class="orchestrator-qr-code">${pairingCode}</strong></p>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <#-- verificationCode is never typed anywhere - only compared by eye against the app
                 screen (QR-jacking countermeasure, docs/07-betrieb.md #5). -->
            <#if verificationCode??>
                <p>Vergleichscode: <strong class="orchestrator-qr-code">${verificationCode}</strong></p>
                <p class="orchestrator-hint">
                    Bestätigen Sie in der App nur, wenn dort derselbe Code angezeigt wird.
                </p>
            </#if>
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
                Demo-Link: öffnet die App direkt (ohne Kamera) mit vorbefülltem Pairing-Code.
            </p>
        </div>

        <form id="kc-orchestrator-tool-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_abandon" value="true">Abbrechen</button>
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
</@layout.registrationLayout>
