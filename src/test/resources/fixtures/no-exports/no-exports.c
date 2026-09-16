/*
 * A shared library that exports nothing, so every reader reports an empty exported set.
 *
 * Both symbols stay out of it for a different reason — one by internal linkage, the other by hidden
 * visibility — and _noop keeps the linker from dropping the library as empty.
 */

static int internal(void) { return 0; }

void __attribute__((visibility("hidden"))) _noop(void) { internal(); }
