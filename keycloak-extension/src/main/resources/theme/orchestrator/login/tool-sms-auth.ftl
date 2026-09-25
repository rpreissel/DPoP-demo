<#import "template.ftl" as layout>
<#import "made-with.ftl" as madeWith>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <@madeWith.note/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="tan" class="${properties.kcLabelClass!}">${t.of("SMS-Code")}</label>
                <input type="text" id="tan" name="tan" class="${properties.kcInputClass!}" autocomplete="one-time-code"/>
                <#if demoTan??>
                    <span class="orchestrator-hint">${t.of("Demo-Code: {wert}", {"wert": demoTan})}</span>
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
