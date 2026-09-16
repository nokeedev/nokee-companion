/*
 * An object archived into a static library.
 *
 * A static library is not a shared library, and the readers say so by refusing to parse one: the
 * archive's "!<arch>" magic is neither ELF's nor Mach-O's. Nothing in this source is read, so it
 * only has to compile.
 */

int add(int a, int b) { return a + b; }
int mul(int a, int b) { return a * b; }
