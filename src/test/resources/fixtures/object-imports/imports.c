// A relocatable object whose ABI-relevant content is what it *imports*: the undefined external
// references a linker must resolve. It also defines external and internal symbols that must NOT be
// reported as imports, so the readers are exercised on the full filter (external + undefined + value 0).
//
// Header-free on purpose: it compiles to an object for any target with no sysroot, so one source yields
// ELF, Mach-O and COFF fixtures. Symbol decoration is target-specific (Mach-O and 32-bit COFF add a
// leading underscore; ELF and 64-bit COFF do not), which the per-format tests account for.

extern int foo(void);            // undefined external function -> an import
extern int bar(void);            // undefined external function -> an import
extern int gvar;                 // undefined external variable -> an import (data, not a function)

static int secret(void) {        // internal linkage -> never an import (and not exported)
	return 7;
}

int local_helper(void);          // defined below -> external but not undefined, so not an import

int entry(void) {                // defined external -> not an import
	return foo() + bar() + gvar + local_helper() + secret();
}

int local_helper(void) {
	return 2;
}
