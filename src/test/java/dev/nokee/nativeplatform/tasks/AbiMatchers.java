package dev.nokee.nativeplatform.tasks;

import dev.nokee.nativeplatform.tasks.AbiBinaryHasher.AbiBinaryHashCode;
import dev.nokee.nativeplatform.tasks.AbiBinaryHasher.ExportedSymbol;
import dev.nokee.nativeplatform.tasks.AbiBinaryHasher.HasExportSymbols;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import static org.hamcrest.Matchers.empty;

/**
 * Matchers over the ABI model. A shared library exposes its exports through {@link HasExportSymbols} as a
 * collection of {@link ExportedSymbol}s; each concrete symbol is a small map-backed model keyed by name
 * plus a format-specific attribute (ELF {@code binding}, Mach-O {@code isWeakBinding}, PE
 * {@code ordinalOrHint}), so a "symbol" matcher checks the name and, where it matters, that attribute.
 */
final class AbiMatchers {
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;

	private AbiMatchers() {}

	static Matcher<AbiBinaryHashCode> sharedLibrary(Matcher<? super Collection<ExportedSymbol>> symbolsMatcher) {
		return new TypeSafeMatcher<AbiBinaryHashCode>() {
			@Override
			protected boolean matchesSafely(AbiBinaryHashCode model) {
				return model instanceof HasExportSymbols
					&& symbolsMatcher.matches(((HasExportSymbols) model).getExportedSymbols());
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("shared library with exported symbols ").appendDescriptionOf(symbolsMatcher);
			}

			@Override
			protected void describeMismatchSafely(AbiBinaryHashCode model, Description description) {
				if (!(model instanceof HasExportSymbols)) {
					description.appendText("was not a shared library exposing exports (").appendValue(model).appendText(")");
					return;
				}
				description.appendText("exported symbols ");
				symbolsMatcher.describeMismatch(((HasExportSymbols) model).getExportedSymbols(), description);
			}
		};
	}

	static Matcher<AbiBinaryHashCode> emptySharedLibrary() {
		return sharedLibrary(empty());
	}

	static Matcher<ExportedSymbol> strongElfSymbol(String name) {
		return exportedSymbol(name, "binding", STB_GLOBAL);
	}

	static Matcher<ExportedSymbol> weakElfSymbol(String name) {
		return exportedSymbol(name, "binding", STB_WEAK);
	}

	static Matcher<ExportedSymbol> strongMachOSymbol(String name) {
		return exportedSymbol(name, "isWeakBinding", false);
	}

	static Matcher<ExportedSymbol> weakMachOSymbol(String name) {
		return exportedSymbol(name, "isWeakBinding", true);
	}

	static Matcher<ExportedSymbol> namedPeSymbol(String name) {
		return exportedSymbol(name);
	}

	static Matcher<ExportedSymbol> ordinalOnlyPeSymbol(int ordinal) {
		return namedPeSymbol("#" + ordinal);
	}

	private static Matcher<ExportedSymbol> exportedSymbol(String name) {
		return new TypeSafeMatcher<ExportedSymbol>() {
			@Override
			protected boolean matchesSafely(ExportedSymbol symbol) {
				return symbol.getName().equals(name.hashCode());
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("exported symbol ").appendValue(name);
			}
		};
	}

	private static Matcher<ExportedSymbol> exportedSymbol(String name, String attributeKey, Object attributeValue) {
		return new TypeSafeMatcher<ExportedSymbol>() {
			@Override
			protected boolean matchesSafely(ExportedSymbol symbol) {
				return symbol.getName().equals(name.hashCode())
					&& symbol instanceof Map
					&& Objects.equals(((Map<?, ?>) symbol).get(attributeKey), attributeValue);
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("exported symbol ").appendValue(name)
					.appendText(" with ").appendText(attributeKey).appendText("=").appendValue(attributeValue);
			}
		};
	}
}
