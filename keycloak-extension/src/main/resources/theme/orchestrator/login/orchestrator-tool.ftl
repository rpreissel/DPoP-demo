<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${toolId}
    <#elseif section = "form">
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#list fields?keys as fieldName>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="${fieldName}" class="${properties.kcLabelClass!}">${fieldName}</label>
                    <#-- Generic scaffold, not a per-tool UI: renders one text input per stepData key
                         (docs/ideen/web-keycloak-kanal.md leaves the exact per-tool form fields to the
                         orchestrator's own tool_api, this plugin doesn't special-case any of them). -->
                    <input type="text" id="${fieldName}" name="${fieldName}" class="${properties.kcInputClass!}"
                           value="${fields[fieldName]!''}" autocomplete="off"/>
                </div>
            </#list>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit">Weiter</button>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_abandon" value="true">Zurück</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
