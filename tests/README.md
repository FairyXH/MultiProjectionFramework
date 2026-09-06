# Regression checks

This directory is host-only. Gradle and CMake do not include it in the module's Java sources,
resources, assets or native libraries. Test doubles and ASan binaries run in temporary host
directories; no test runner or test dependency is packaged in the APK. The template also installs
no Activity/getpid demonstration hooks at runtime.

Run `python3 -m unittest discover -s tests -v` from the repository root. Python 3.10+, a JDK and Clang++ with AddressSanitizer
are required. The documentation link check also uses NDK r29 when available. Tests use temporary copies and synthetic ELF files; they do not modify the working
source or install anything on a device. `capstone` is not needed for the non-disassembly checks.

Java checks compile the actual module sources against small Android/framework test doubles to
exercise failure, persistence and gesture contracts on the host. Native checks compile the actual
portable scanner function bodies with AddressSanitizer; they do not invoke target app memory. They do not replace Android runtime tests.
Run `./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug` for real Android API and build
validation. For before/after comparison, set `TEMPLATE_TEST_ROOT` to the source snapshot to test.
