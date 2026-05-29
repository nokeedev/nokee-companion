/*
 * Shared library with a mix of strong and weak exported symbols.
 *
 * Symbols:
 *   - strong_func() : strong global function
 *   - weak_func()   : weak global function (can be overridden by the linker)
 *   - strong_var    : strong global variable
 *   - weak_var      : weak global variable
 *
 * Used to verify that the ABI extractor correctly distinguishes strong from
 * weak symbols, and that a change from weak to strong (or vice versa) is
 * reflected as an ABI change.
 *
 * Note: __attribute__((weak)) is a GCC/Clang extension; not available on MSVC.
 * This fixture is for ELF and Mach-O only.
 */

#define EXPORT __attribute__((visibility("default")))
#define WEAK   __attribute__((weak, visibility("default")))

EXPORT int strong_func(void) { return 1; }
WEAK   int weak_func(void)   { return 0; }

EXPORT int strong_var = 10;
WEAK   int weak_var   = 0;
