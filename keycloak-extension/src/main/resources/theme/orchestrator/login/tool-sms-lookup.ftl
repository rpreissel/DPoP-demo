<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "form">
        <h2 class="${properties.kcFormHeaderClass!}">${title}</h2>
        <p class="${properties.kcLabelClass!}">${hint}</p>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#if step == "tanInput">
                <div class="${properties.kcFormGroupClass!}">
                    <label for="tan" class="${properties.kcLabelClass!}">SMS-Code</label>
                    <input type="text" id="tan" name="tan" class="${properties.kcInputClass!}" autocomplete="one-time-code"/>
                    <#if demoTan??>
                        <span class="${properties.kcLabelClass!}">Demo-Code: ${demoTan}</span>
                    </#if>
                </div>
            <#else>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="email" class="${properties.kcLabelClass!}">E-Mail-Adresse</label>
                    <input type="email" id="email" name="email" class="${properties.kcInputClass!}" autocomplete="email"/>
                    <#if demoEmail??>
                        <span class="${properties.kcLabelClass!}">Demo-E-Mail: ${demoEmail}</span>
                    </#if>
                </div>
            </#if>
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
