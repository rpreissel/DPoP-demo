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
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="code" class="${properties.kcLabelClass!}">${t.of("Bestätigungscode")}</label>
                <input type="text" id="code" name="code" class="${properties.kcInputClass!}" autocomplete="off"/>
                <#if demoTan??>
                    <span class="orchestrator-hint">${t.of("Demo-Code: {wert}", {"wert": demoTan})}</span>
                </#if>
            </div>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_back" value="true">${t.of("Zurück")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
