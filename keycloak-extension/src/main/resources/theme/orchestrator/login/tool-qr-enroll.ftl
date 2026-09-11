<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "form">
        <h2 class="${properties.kcFormHeaderClass!}">Web-Login per QR erlauben</h2>
        <p class="${properties.kcLabelClass!}">
            Erlaubt, dass dieses Konto künftig einen Web-Login per QR-Code bestätigen kann
            (docs/ideen/qr-login-ueber-app.md). Kein zusätzliches Passwort oder Gerät nötig.
        </p>

        <form id="kc-orchestrator-tool-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit">Aktivieren</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
