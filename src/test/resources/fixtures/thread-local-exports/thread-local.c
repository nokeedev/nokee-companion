/*
 * An ELF shared library exporting a thread-local variable next to an ordinary one.
 *
 * STT_TLS is the one symbol type the link result depends on: a thread-local definition is reached
 * through its own relocations, and ld refuses to resolve a non-TLS reference against it. This fixture
 * puts a symbol of each type in the same exported set so the type the reader reports is what tells
 * them apart.
 *
 * Mach-O reaches a thread-local through a thread-local variable descriptor rather than a symbol type,
 * so this fixture is ELF only.
 */

#define EXPORT __attribute__((visibility("default")))

EXPORT __thread int tls_var = 1;
EXPORT int plain_var = 2;

EXPORT int read_both(void) { return tls_var + plain_var; }
