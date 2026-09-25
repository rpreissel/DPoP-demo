<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#if step == "card">
                <p class="orchestrator-hint">${t.of("Demo-Modus: Das Auslesen der Karte wird simuliert.")}</p>
                <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"name":"name","vorname":"vorname","geburtsdatum":"geburtsdatum","strasse":"strasse","plz":"plz","ort":"ort"}' />
                <div class="orchestrator-grid-2">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="name" class="${properties.kcLabelClass!}">${t.of("Nachname")}</label>
                        <input type="text" id="name" name="name" class="${properties.kcInputClass!}"/>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="vorname" class="${properties.kcLabelClass!}">${t.of("Vorname")}</label>
                        <input type="text" id="vorname" name="vorname" class="${properties.kcInputClass!}"/>
                    </div>
                </div>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="geburtsdatum" class="${properties.kcLabelClass!}">${t.of("Geburtsdatum")}</label>
                    <input type="date" id="geburtsdatum" name="geburtsdatum" class="${properties.kcInputClass!}"/>
                </div>
                <#-- Die Karte liefert Straße und Hausnummer in einem Feld (Street). -->
                <div class="${properties.kcFormGroupClass!}">
                    <label for="strasse" class="${properties.kcLabelClass!}">${t.of("Straße und Hausnummer")}</label>
                    <input type="text" id="strasse" name="strasse" class="${properties.kcInputClass!}"/>
                </div>
                <div class="orchestrator-grid-2">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="plz" class="${properties.kcLabelClass!}">${t.of("PLZ")}</label>
                        <input type="text" id="plz" name="plz" class="${properties.kcInputClass!}"/>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="ort" class="${properties.kcLabelClass!}">${t.of("Ort")}</label>
                        <input type="text" id="ort" name="ort" class="${properties.kcInputClass!}"/>
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
