<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${t.of("Anmeldung nicht möglich")}
    </#if>
</@layout.registrationLayout>
