#include <stdio.h>
extern "C" {
	#include "hello.h"
}

void DLL_FUNC sayHello() {
	#ifdef FRENCH
	printf("Bonjour, Monde!\n");
	#else
	printf("Hello, World!\n");
	#endif

	fflush(stdout);
}

// Ensure consistent asm name mapping on all platforms
#if !defined(_MSC_VER)
extern int sumx(int a, int b) asm("_sumx");
#endif

int DLL_FUNC sum(int a, int b) {
	return sumx(a, b);
}
