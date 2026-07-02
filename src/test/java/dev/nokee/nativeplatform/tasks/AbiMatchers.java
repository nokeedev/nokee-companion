package dev.nokee.nativeplatform.tasks;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.Collection;
import java.util.List;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;

final class AbiMatchers {
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;

	private AbiMatchers() {}

	static Matcher<AbiModel> sharedLibrary(Matcher<? super Collection<ExportedSymbol>> symbolsMatcher) {
		return new TypeSafeMatcher<AbiModel>() {
			@Override
			protected boolean matchesSafely(AbiModel model) {
				if (!(model instanceof SharedLibraryAbiModel)) return false;
				return symbolsMatcher.matches(((SharedLibraryAbiModel) model).getExportedSymbols().get());
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("shared library with exported symbols ").appendDescriptionOf(symbolsMatcher);
			}

			@Override
			protected void describeMismatchSafely(AbiModel model, Description description) {
				if (!(model instanceof SharedLibraryAbiModel)) {
					description.appendText("was ").appendValue(model.getClass().getSimpleName());
				} else {
					List<? extends ExportedSymbol> symbols = ((SharedLibraryAbiModel) model).getExportedSymbols().get();
					description.appendText("exported symbols ");
					symbolsMatcher.describeMismatch(symbols, description);
				}
			}
		};
	}

	static Matcher<AbiModel> emptySharedLibrary() {
		return sharedLibrary(empty());
	}

	static Matcher<ExportedSymbol> strongElfSymbol(String name) {
		return elfSymbol(name, STB_GLOBAL);
	}

	static Matcher<ExportedSymbol> weakElfSymbol(String name) {
		return elfSymbol(name, STB_WEAK);
	}

	private static Matcher<ExportedSymbol> elfSymbol(String name, int binding) {
		String bindingLabel = binding == STB_GLOBAL ? "global" : "weak";
		return new TypeSafeMatcher<ExportedSymbol>() {
			@Override
			protected boolean matchesSafely(ExportedSymbol sym) {
				if (!(sym instanceof ElfExportedSymbol)) return false;
				ElfExportedSymbol elf = (ElfExportedSymbol) sym;
				return name.equals(elf.getName().get()) && elf.getBinding().get() == binding;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("ELF " + bindingLabel + " symbol '" + name + "'");
			}
		};
	}

	static Matcher<ExportedSymbol> strongMachOSymbol(String name) {
		return machOSymbol(name, false);
	}

	static Matcher<ExportedSymbol> weakMachOSymbol(String name) {
		return machOSymbol(name, true);
	}

	private static Matcher<ExportedSymbol> machOSymbol(String name, boolean weak) {
		return new TypeSafeMatcher<ExportedSymbol>() {
			@Override
			protected boolean matchesSafely(ExportedSymbol sym) {
				if (!(sym instanceof MachOExportedSymbol)) return false;
				MachOExportedSymbol macho = (MachOExportedSymbol) sym;
				return name.equals(macho.getName().get()) && macho.getWeak().get() == weak;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("Mach-O " + (weak ? "weak" : "strong") + " symbol '" + name + "'");
			}
		};
	}

	static Matcher<ExportedSymbol> namedPeSymbol(String name) {
		return new TypeSafeMatcher<ExportedSymbol>() {
			@Override
			protected boolean matchesSafely(ExportedSymbol sym) {
				return sym instanceof PeExportedSymbol && name.equals(sym.getName().get());
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("PE symbol '" + name + "'");
			}
		};
	}

	static Matcher<ExportedSymbol> ordinalOnlyPeSymbol(int ordinal) {
		return namedPeSymbol("#" + ordinal);
	}
}
