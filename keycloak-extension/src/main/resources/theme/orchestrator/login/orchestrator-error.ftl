<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${t.of("Anmeldung nicht möglich")}
    <#elseif section = "form">
        <@pageNotes.notes/>
    </#if>
</@layout.registrationLayout>
