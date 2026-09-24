<#-- Shared by every tool-*.ftl that offers a demo persona picker (mirrors the App channel's
     DemoPersonPicker.tsx): a free-text input stays free-text always (manual input must always be
     possible), this only adds a <select> above it that, on change, fills the given input ids from
     one of demo.persons' fields, and prefills them from the first persona on load (the only prefill -
     the templates themselves carry no test values, so demo values off means empty forms, ADR-28). personsJson comes from AbstractWebToolRendererFactory#demoPersonsJson;
     fieldMapJson is a JSON object literal mapping input-element-id -> person field name, e.g.
     '{"email":"email"}' or '{"kvnr":"kvnr","name":"name","vorname":"vorname","fsc":"fscCode"}'. -->
<#macro personPicker personsJson fieldMapJson>
    <#if personsJson?? && personsJson != "null">
        <div class="${properties.kcFormGroupClass!} orchestrator-demo-picker">
            <label for="demoPerson" class="${properties.kcLabelClass!}"><span class="orchestrator-demo-tag">${t.of("Demo")}</span> ${t.of("Testperson übernehmen")}</label>
            <select id="demoPerson" class="${properties.kcInputClass!}">
                <option value="">${t.of("— manuell eingeben —")}</option>
            </select>
        </div>
        <script>
            (function () {
                var persons = ${personsJson?no_esc};
                var fieldMap = ${fieldMapJson?no_esc};
                var select = document.getElementById('demoPerson');
                persons.forEach(function (p, i) {
                    var opt = document.createElement('option');
                    opt.value = String(i);
                    opt.textContent = p.vorname + ' ' + p.name + ' (' + (p.email || p.kvnr || p.personId) + ')';
                    select.appendChild(opt);
                });
                function apply(p) {
                    Object.keys(fieldMap).forEach(function (inputId) {
                        var el = document.getElementById(inputId);
                        var key = fieldMap[inputId];
                        if (el && p[key] !== undefined) el.value = p[key] == null ? '' : p[key];
                    });
                }
                select.addEventListener('change', function () {
                    if (select.value === '') return;
                    apply(persons[parseInt(select.value, 10)]);
                });
                // Prefilled from the first persona, like the app's forms - and only then: without
                // demo values (ADR-28) there are no personas, and the form starts empty.
                // The inputs follow this script in the page, so wait until they exist.
                if (persons.length > 0) {
                    select.value = '0';
                    document.addEventListener('DOMContentLoaded', function () { apply(persons[0]); });
                }
            })();
        </script>
    </#if>
</#macro>
