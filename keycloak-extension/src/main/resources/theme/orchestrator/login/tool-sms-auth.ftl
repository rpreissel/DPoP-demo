<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "form">
        <h2 class="${properties.kcFormHeaderClass!}">${title}</h2>
        <p class="${properties.kcLabelClass!}">${hint}</p>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="tan" class="${properties.kcLabelClass!}">SMS-Code</label>
                <input type="text" id="tan" name="tan" class="${properties.kcInputClass!}" autocomplete="one-time-code"/>
                <#if demoTan??>
                    <span class="${properties.kcLabelClass!}">Demo-Code: ${demoTan}</span>
                </#if>
            </div>
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
