/*
 * Shared library with three named exported symbols:
 *   - greet()   : function, returns int
 *   - compute() : function, returns int
 *   - value     : global variable, int
 *
 * Used to verify that the ABI extractor reports all three symbols.
 */

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

EXPORT int greet(void) { return 42; }
EXPORT int compute(int x) { return x * 2; }
EXPORT int value = 7;
