/*
 * An ELF shared library built against a version script, so its exported symbols carry versions.
 *
 * The .symver directives define one name, compat, at two versions at once: LIB_1.0 is the earlier
 * definition and LIB_2.0 the one an unversioned reference binds to — the distinction .gnu.version
 * spells as VER_NDX_HIDDEN, and the reader reports as the default version.
 *
 * stable is defined at LIB_2.0 alone, so it is the case of a name with a single, default, version.
 *
 * Mach-O and PE have no counterpart to any of this, so this fixture is ELF only.
 */

int compat_impl(void)  { return 1; }
int current_impl(void) { return 2; }

__asm__(".symver compat_impl,compat@LIB_1.0");
__asm__(".symver current_impl,compat@@LIB_2.0");

int stable(void) { return 3; }
