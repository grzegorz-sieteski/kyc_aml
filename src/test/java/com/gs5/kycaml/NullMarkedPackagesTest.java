package com.gs5.kycaml;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-12 (stage 6, SR-14). NullAway checks only {@code @NullMarked} code, so a package without it is a
 * package the null-safety gate silently skips: an adapter could dereference a {@code @Nullable} and the
 * build would stay green. Every production package must be marked.
 */
class NullMarkedPackagesTest {

    @Test
    @DisplayName("every production package under com.gs5.kycaml is @NullMarked")
    void shouldMarkEveryProductionPackage() {
        var packages = new TreeSet<String>();
        new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.gs5.kycaml")
                .forEach(type -> packages.add(type.getPackageName()));
        assertThat(packages).as("production packages found").contains("com.gs5.kycaml.risk.adapters.web");

        var unmarked = packages.stream().filter(name -> !isNullMarked(name)).toList();

        assertThat(unmarked).as("packages NullAway does not check").isEmpty();
    }

    private static boolean isNullMarked(String packageName) {
        try {
            return Class.forName(packageName + ".package-info").isAnnotationPresent(NullMarked.class);
        } catch (ClassNotFoundException noPackageInfo) {
            return false;
        }
    }
}
