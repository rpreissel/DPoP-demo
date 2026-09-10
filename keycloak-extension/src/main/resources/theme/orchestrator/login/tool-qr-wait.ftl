<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "form">
        <h2 class="${properties.kcFormHeaderClass!}">${title}</h2>
        <p class="${properties.kcLabelClass!}">${hint}</p>

        <div class="${properties.kcFormGroupClass!}" style="text-align:center;">
            <img src="${qrDataUri}" alt="QR-Code" width="220" height="220"/>
        </div>

        <div class="${properties.kcFormGroupClass!}" style="text-align:center;">
            <#-- Manuelle Eingabe ist ein gleichwertiger Weg, kein Fallback (docs/ideen/qr-login-
                 ueber-app.md #6) - der Code muss deshalb hier auch gut lesbar/abschreibbar stehen,
                 nicht nur im QR-Bild bzw. versteckt in der Demo-Link-URL. -->
            <p>Pairing-Code: <strong style="font-size:1.4em; letter-spacing:0.1em;">${pairingCode}</strong></p>
        </div>

        <div class="${properties.kcFormGroupClass!}" style="text-align:center;">
            <#-- verificationCode is never typed anywhere - only compared by eye against the app
                 screen (QR-jacking countermeasure, docs/ideen/qr-login-ueber-app.md #6). -->
            <#if verificationCode??>
                <p>Vergleichscode: <strong style="font-size:1.4em;">${verificationCode}</strong></p>
                <p class="${properties.kcLabelClass!}">
                    Bestätigen Sie in der App nur, wenn dort derselbe Code angezeigt wird.
                </p>
            </#if>
        </div>

        <div class="${properties.kcFormGroupClass!}" style="text-align:center;">
            <a href="${deepLink}">${deepLink}</a>
            <p class="${properties.kcLabelClass!}">
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
             at this rate: an InProgress outcome never charges any attempt/login counter
             (docs/ideen/qr-login-ueber-app.md #7). Stops once the page navigates away. -->
        <script>
            setTimeout(function () {
                document.getElementById("kc-orchestrator-tool-form").submit();
            }, 3000);
        </script>
    </#if>
</@layout.registrationLayout>
