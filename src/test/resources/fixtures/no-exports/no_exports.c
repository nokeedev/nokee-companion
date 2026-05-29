/*
 * Shared library with no exported symbols.
 *
 * All symbols use hidden visibility so the dynamic symbol table is empty.
 * Used to verify that the ABI extractor returns an empty symbol list.
 */

static int internal(void) { return 0; }

/* Ensure the linker does not strip the library as empty */
void __attribute__((visibility("hidden"))) _noop(void) { internal(); }
