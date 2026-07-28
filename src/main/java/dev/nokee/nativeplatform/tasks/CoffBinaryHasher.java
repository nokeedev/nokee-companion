package dev.nokee.nativeplatform.tasks;

import java.util.function.Consumer;

class CoffBinaryHasher {
	public void visitImports(CoffBlob.CoffObjectBlob coff, Consumer<? super String> visitor) {
		try (CoffBlob.CoffStringTable strings = coff.strings()) {
			try (CoffBlob.CoffSymbolTable symbols = coff.symbols()) {
				for (CoffBlob.CoffSymbol symbol : symbols) {
					long strx = symbol.strx();
					if (strx == -1) {
						visitor.accept(strings.get(strx));
					} else {
						visitor.accept(symbol.name());
					}
				}
			}
		}
	}
}
