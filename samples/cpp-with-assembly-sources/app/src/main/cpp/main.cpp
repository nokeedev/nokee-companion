#include <iostream>
extern "C" {
	#include "hello.h"
}

int main () {
  sayHello();
  std::cout << sum(5, 7);
  return 0;
}
