/*
 * Object file compiled into a static archive.
 *
 * The functions are not exported as a shared library ABI — they are linked
 * directly into the consumer at build time.
 *
 * Used to verify that the ABI extractor returns StaticLibraryAbiModel for
 * Unix static archives (.a) so that they are tracked byte-to-byte.
 */

int add(int a, int b) { return a + b; }
int mul(int a, int b) { return a * b; }
