<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${t.of("Web-Login per QR-Code erlauben")}
    <#elseif section = "form">
        <p class="orchestrator-subtitle">
            ${t.of("Erlaubt, dass dieses Konto künftig einen Web-Login per QR-Code bestätigen kann. Kein zusätzliches Passwort oder Gerät nötig.")}
        </p>

        <form id="kc-orchestrator-tool-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!}"
                        type="submit">${t.of("Aktivieren")}</button>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Abbrechen")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
