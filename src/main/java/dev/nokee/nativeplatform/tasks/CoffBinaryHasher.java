package dev.nokee.nativeplatform.tasks;

import java.util.function.Consumer;

import static dev.nokee.nativeplatform.tasks.CoffBlob.IMAGE_SYM_CLASS_EXTERNAL;
import static dev.nokee.nativeplatform.tasks.CoffBlob.IMAGE_SYM_CLASS_WEAK_EXTERNAL;
import static dev.nokee.nativeplatform.tasks.CoffBlob.IMAGE_SYM_UNDEFINED;

class CoffBinaryHasher {
	public void visitImports(CoffBlob.CoffObjectBlob coff, Consumer<? super String> visitor) {
		try (CoffBlob.CoffStringTable strings = coff.strings()) {
			try (CoffBlob.CoffSymbolTable symbols = coff.symbols()) {
				for (CoffBlob.CoffSymbol symbol : symbols) {
					if (isImportSymbol(symbol)) {
						String name = nameOf(symbol, strings);
						if (!name.isEmpty()) {
							visitor.accept(name);
						}
					}
				}
			}
		}
	}

	/**
	 * Whether this symbol is one the object asks another object for, which is what the link resolves and so
	 * what a narrowed snapshot keeps. The symbol table also carries the object's own definitions and its
	 * bookkeeping records — file names, section symbols, statics — none of which are imports.
	 */
	private static boolean isImportSymbol(CoffBlob.CoffSymbol symbol) {
		return isUndefined(symbol) && isExternal(symbol) && !isCommonSymbol(symbol);
	}

	/** Whether the object leaves the symbol for someone else to define. */
	private static boolean isUndefined(CoffBlob.CoffSymbol symbol) {
		return symbol.sectionNumber() == IMAGE_SYM_UNDEFINED;
	}

	/** Whether the symbol is visible to the linker at all, as opposed to a static or a bookkeeping record. */
	private static boolean isExternal(CoffBlob.CoffSymbol symbol) {
		int storageClass = symbol.storageClass();
		return storageClass == IMAGE_SYM_CLASS_EXTERNAL || storageClass == IMAGE_SYM_CLASS_WEAK_EXTERNAL;
	}

	/**
	 * Whether the symbol asks the linker to allocate storage for it, giving the size as its value. It looks
	 * undefined because no section holds it, but it is a definition rather than a reference, so it is not an
	 * import.
	 */
	private static boolean isCommonSymbol(CoffBlob.CoffSymbol symbol) {
		return symbol.sectionNumber() == IMAGE_SYM_UNDEFINED && symbol.value() != 0;
	}

	/**
	 * The symbol's name, from whichever of the two places COFF put it. A name of eight characters or fewer
	 * sits inline in the symbol record, which {@link CoffBlob.CoffSymbol#strx()} reports as {@literal -1}.
	 * Anything longer is held in the string table at the offset it returns instead.
	 */
	private static String nameOf(CoffBlob.CoffSymbol symbol, CoffBlob.CoffStringTable strings) {
		long strx = symbol.strx();
		return strx == -1 ? symbol.name() : strings.get(strx);
	}
}
