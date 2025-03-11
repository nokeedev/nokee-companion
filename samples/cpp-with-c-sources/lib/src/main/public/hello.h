#ifdef _WIN32
#define DLL_FUNC __declspec(dllexport)
#else
#define DLL_FUNC
#endif

void DLL_FUNC sayHello();
int DLL_FUNC sum(int a, int b);
