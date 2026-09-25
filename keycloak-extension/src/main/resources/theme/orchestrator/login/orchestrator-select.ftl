<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<@layout.registrationLayout displayMessage=true displayInfo=(offerRegistration!false); section>
    <#if section = "header">
        ${pageTitle!t.of("Anmeldemethode wählen")}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if description??>
            <p class="orchestrator-subtitle">${description}</p>
        </#if>
        <form id="kc-orchestrator-select-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#list options as option>
                <div class="${properties.kcFormGroupClass!}">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                            type="submit" name="toolId" value="${option}">${optionLabels[option]!option}</button>
                </div>
            </#list>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Abbrechen")}</button>
            </div>
        </form>
    <#elseif section = "info">
        <#-- Same link the native login form shows; the realm's registration flow runs the
             orchestrator's REGISTER journey (identification by FSC/eID, then a login method). -->
        <div id="kc-registration">
            <span>${t.of("Noch kein Konto?")} <a href="${url.registrationUrl}">${t.of("Registrieren")}</a></span>
        </div>
    </#if>
</@layout.registrationLayout>
