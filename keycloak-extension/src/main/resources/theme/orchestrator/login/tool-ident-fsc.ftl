<#import "template.ftl" as layout>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "form">
        <h2 class="${properties.kcFormHeaderClass!}">${title}</h2>
        <p class="${properties.kcLabelClass!}">${hint}</p>
        <p class="${properties.kcLabelClass!}">Testdaten vorbelegt: A123456789 / Muster, Max / Code VALIDCODE</p>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"kvnr":"kvnr","name":"name","vorname":"vorname","fsc":"fscCode"}' />
            <#if needsKvnr!true>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="kvnr" class="${properties.kcLabelClass!}">Krankenversichertennummer</label>
                    <input type="text" id="kvnr" name="kvnr" class="${properties.kcInputClass!}" value="A123456789"/>
                </div>
            </#if>
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
            <#if needsFsc!false>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="fsc" class="${properties.kcLabelClass!}">Freischaltcode</label>
                    <input type="text" id="fsc" name="fsc" class="${properties.kcInputClass!}" value="VALIDCODE"/>
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
