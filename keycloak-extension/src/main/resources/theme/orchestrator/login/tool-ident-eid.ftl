<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#if step == "card">
                <p class="orchestrator-hint">${t.of("Demo-Modus: Das Auslesen der Karte wird simuliert.")}</p>
                <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"familyName":"familyName","givenNames":"givenNames","birthDate":"birthDate","streetAddress":"streetAddress","postalCode":"postalCode","locality":"locality"}' />
                <div class="orchestrator-grid-2">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="familyName" class="${properties.kcLabelClass!}">${t.of("Nachname")}</label>
                        <input type="text" id="familyName" name="familyName" class="${properties.kcInputClass!}"/>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="givenNames" class="${properties.kcLabelClass!}">${t.of("Vorname")}</label>
                        <input type="text" id="givenNames" name="givenNames" class="${properties.kcInputClass!}"/>
                    </div>
                </div>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="birthDate" class="${properties.kcLabelClass!}">${t.of("Geburtsdatum")}</label>
                    <input type="date" id="birthDate" name="birthDate" class="${properties.kcInputClass!}"/>
                </div>
                <#-- Die Karte liefert Straße und Hausnummer in einem Feld (Street). -->
                <div class="${properties.kcFormGroupClass!}">
                    <label for="streetAddress" class="${properties.kcLabelClass!}">${t.of("Straße und Hausnummer")}</label>
                    <input type="text" id="streetAddress" name="streetAddress" class="${properties.kcInputClass!}"/>
                </div>
                <div class="orchestrator-grid-2">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="postalCode" class="${properties.kcLabelClass!}">${t.of("PLZ")}</label>
                        <input type="text" id="postalCode" name="postalCode" class="${properties.kcInputClass!}"/>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="locality" class="${properties.kcLabelClass!}">${t.of("Ort")}</label>
                        <input type="text" id="locality" name="locality" class="${properties.kcInputClass!}"/>
                    </div>
                </div>
            <#elseif step == "pin">
                <div class="${properties.kcFormGroupClass!}">
                    <label for="pin" class="${properties.kcLabelClass!}">${t.of("eID-PIN")}</label>
                    <input type="text" id="pin" name="pin" class="${properties.kcInputClass!}" autocomplete="off" value="123456"/>
                </div>
            </#if>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_back" value="true">${t.of("Zurück")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
