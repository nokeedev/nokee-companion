#include "linked_list.h"
#include "node.h" // only for product

int main(int argc, char* argv[]) {
	linked_list list = linked_list();
	list.add("foo");
	list.add("bar");
	return list.size() == 2 ? 0 : -1;
}
