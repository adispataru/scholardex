package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessibilityTemplateContractTest {

    @Test
    void sharedTabFragmentExposesAnInitialSelectedTabAndVisiblePanel() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/fragments.html"));

        assertTrue(template.contains("aria-selected=${iterStat.first ? 'true' : 'false'}"));
        assertTrue(template.contains("th:hidden=\"${!iterStat.first}\""));
        assertFalse(template.contains("aria-selected='false',"));
    }

    @Test
    void representativeAdminTemplatesExposeLiveRegionsAndRealModalButtons() throws Exception {
        String groups = Files.readString(Path.of("src/main/resources/templates/admin/groups.html"));
        String activities = Files.readString(Path.of("src/main/resources/templates/admin/activities.html"));
        String activityEdit = Files.readString(Path.of("src/main/resources/templates/admin/activities-edit.html"));
        String reportEdit = Files.readString(Path.of("src/main/resources/templates/admin/edit-individualReport.html"));
        String users = Files.readString(Path.of("src/main/resources/templates/admin/users.html"));
        String publications = Files.readString(Path.of("src/main/resources/templates/admin/scholardex-publications.html"));

        assertTrue(groups.contains("role=\"alert\""));
        assertTrue(groups.contains("role=\"status\""));
        assertTrue(groups.contains("<button type=\"button\" class=\"btn btn-primary btn-sm\" data-toggle=\"modal\" data-target=\"#createGroupModal\">"));
        assertTrue(groups.contains("<button type=\"button\" class=\"btn btn-primary btn-sm\" data-toggle=\"modal\" data-target=\"#importGroupModal\">"));
        assertFalse(groups.contains("<a href=\"#\" class=\"btn btn-primary btn-sm\" data-toggle=\"modal\" data-target=\"#createGroupModal\">"));

        assertTrue(activities.contains("<button type=\"button\" class=\"btn btn-primary btn-sm\" data-toggle=\"modal\" data-target=\"#createActivityModal\">"));
        assertFalse(activities.contains("<a href=\"#\" class=\"btn btn-primary btn-sm\" data-toggle=\"modal\" data-target=\"#createActivityModal\">"));
        assertTrue(activities.contains("aria-labelledby=\"createActivityModalLabel\""));
        assertTrue(activities.contains("aria-describedby=\"createActivityModalDescription\""));

        assertTrue(activityEdit.contains("aria-label=\"Add field\""));
        assertTrue(activityEdit.contains("aria-label=\"Remove field\""));
        assertTrue(activityEdit.contains("aria-label=\"Add allowed value\""));
        assertTrue(activityEdit.contains("aria-label=\"Remove allowed value\""));
        assertTrue(activityEdit.contains("aria-label=\"Add referenced field\""));
        assertTrue(activityEdit.contains("aria-label=\"Remove referenced field\""));

        assertTrue(reportEdit.contains("aria-label=\"Add report indicator\""));
        assertTrue(reportEdit.contains("aria-label=\"Remove report indicator\""));
        assertTrue(reportEdit.contains("aria-label=\"Add criterion\""));
        assertTrue(reportEdit.contains("aria-label=\"Remove criterion\""));
        assertTrue(reportEdit.contains("aria-label=\"Add criterion threshold\""));
        assertTrue(reportEdit.contains("aria-label=\"Remove criterion threshold\""));
        assertTrue(reportEdit.contains("value=\"__not_exported__\""));
        assertTrue(reportEdit.contains("Intentionally not exported"));

        assertTrue(users.contains("role=\"alert\""));
        assertTrue(users.contains("role=\"status\""));
        assertTrue(users.contains("aria-describedby=\"createUserModalDescription\""));
        assertTrue(users.contains("id=\"edit-user-feedback\" class=\"app-form-feedback\" role=\"status\" aria-live=\"polite\""));
        assertTrue(publications.contains("role=\"alert\""));
        assertTrue(publications.contains("role=\"status\""));
    }
}
