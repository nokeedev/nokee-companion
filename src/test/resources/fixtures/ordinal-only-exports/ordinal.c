/*
 * DLL with ordinal-only exports (no symbol names in the export table).
 *
 * When compiled with ordinal.def (NONAME directive), the functions are
 * exported only by ordinal number — their names do not appear in the
 * PE export table or in the import library.
 *
 * In the import library, ordinal-only entries have NameType=IMPORT_ORDINAL (0),
 * so no symbol name is stored. The ABI extractor represents them as "#<ordinal>".
 *
 * Used to verify ordinal-only export handling in ImportLibraryAbiExtractor.
 */

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

EXPORT int func_one(void) { return 1; }
EXPORT int func_two(void) { return 2; }
