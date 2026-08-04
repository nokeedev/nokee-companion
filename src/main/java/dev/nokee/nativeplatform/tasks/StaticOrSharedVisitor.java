package dev.nokee.nativeplatform.tasks;

import java.nio.file.Path;

public interface StaticOrSharedVisitor {
	void visitImports(Object symbol);
	void visitStaticLib(Path path);
	void visitBrokenStaticLib(Path path);
	void visitUnknownLib(Path path);
	void visitSharedLib(AbiBinaryHasher.HasExportSymbols hashcode);
}
