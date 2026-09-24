<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="password" class="${properties.kcLabelClass!}">${t.of("Passwort")}</label>
                <input type="password" id="password" name="password" class="${properties.kcInputClass!}" autocomplete="off"/>
                <#if demoPassword??>
                    <span class="orchestrator-hint">${t.of("Demo-Passwort: {wert}", {"wert": demoPassword})}</span>
                </#if>
            </div>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Zurück")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
