<#import "template.ftl" as layout>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <p class="orchestrator-hint">Testdaten vorbelegt: A123456789 / Muster, Max / Code VALIDCODE</p>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"kvnr":"kvnr","name":"name","vorname":"vorname","fsc":"fscCode"}' />
            <#if needsKvnr!true>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="kvnr" class="${properties.kcLabelClass!}">Krankenversichertennummer</label>
                    <input type="text" id="kvnr" name="kvnr" class="${properties.kcInputClass!}" value="A123456789"/>
                </div>
            </#if>
            <#if (needsName!true) || (needsVorname!true)>
                <div class="orchestrator-grid-2">
                    <#if needsName!true>
                        <div class="${properties.kcFormGroupClass!}">
                            <label for="name" class="${properties.kcLabelClass!}">Nachname</label>
                            <input type="text" id="name" name="name" class="${properties.kcInputClass!}" value="Muster"/>
                        </div>
                    </#if>
                    <#if needsVorname!true>
                        <div class="${properties.kcFormGroupClass!}">
                            <label for="vorname" class="${properties.kcLabelClass!}">Vorname</label>
                            <input type="text" id="vorname" name="vorname" class="${properties.kcInputClass!}" value="Max"/>
                        </div>
                    </#if>
                </div>
            </#if>
            <#if needsFsc!false>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="fsc" class="${properties.kcLabelClass!}">Freischaltcode</label>
                    <input type="text" id="fsc" name="fsc" class="${properties.kcInputClass!}" value="VALIDCODE"/>
                </div>
            </#if>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">Weiter</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">Zurück</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
