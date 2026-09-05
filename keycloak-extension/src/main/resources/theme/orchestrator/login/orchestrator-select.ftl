<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        Anmeldemethode wählen
    <#elseif section = "form">
        <form id="kc-orchestrator-select-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#list options as option>
                <div class="${properties.kcFormGroupClass!}">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                            type="submit" name="toolId" value="${option}">${optionLabels[option]!option}</button>
                </div>
            </#list>
        </form>
    </#if>
</@layout.registrationLayout>
