/*
 * A shared library whose exported symbols cover every distinction the ABI readers report.
 *
 * Exported, one per combination the readers tell apart:
 *   - strong_func / weak_func : a function at each binding
 *   - strong_var / weak_var   : a 4-byte variable at each binding
 *   - uses_internals          : a function, so the two symbols below are referenced and kept
 *
 * Not exported, so a reader that widens its walk past the exported set shows up as an extra entry:
 *   - hidden_func   : external linkage, hidden visibility
 *   - internal_func : internal linkage
 *
 * ELF spells these names as written; Mach-O prefixes each with an underscore.
 */

#define EXPORT __attribute__((visibility("default")))
#define WEAK   __attribute__((weak, visibility("default")))
#define HIDDEN __attribute__((visibility("hidden")))

EXPORT int strong_func(void) { return 1; }
WEAK   int weak_func(void)   { return 0; }

EXPORT int strong_var = 10;
WEAK   int weak_var   = 0;

HIDDEN int hidden_func(void) { return 2; }
static int internal_func(void) { return 3; }

EXPORT int uses_internals(void) { return hidden_func() + internal_func(); }
