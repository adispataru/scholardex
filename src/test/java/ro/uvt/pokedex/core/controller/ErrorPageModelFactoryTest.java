package ro.uvt.pokedex.core.controller;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorPageModelFactoryTest {

    /**
     * Real bundles, so a missing key fails here. Reloadable (not ResourceBundleMessageSource) on purpose:
     * java.util.ResourceBundle caches per (basename, locale, classloader) across the whole test JVM, so a
     * bundle another test loaded first decided this one's answer.
     */
    private static ReloadableResourceBundleMessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void errorModelResolvesLocalizedCopyForTheStatus() {
        ErrorPageModelFactory factory = new ErrorPageModelFactory(messageSource());
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("ro"));
        try {
            factory.apply(model, request, 404);
            assertEquals("Pagina nu a fost găsită", model.get("errorTitle"));
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }

        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        try {
            factory.apply(model, request, 404);
            assertEquals("Page not found", model.get("errorTitle"));
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void errorModelIncludesRequestUriForSharedNavbar() {
        ErrorPageModelFactory factory = new ErrorPageModelFactory(messageSource());
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/admin/group-reports/apply");

        factory.apply(model, request, 500);

        assertEquals("/admin/group-reports/apply", model.get("requestUri"));
    }
}
