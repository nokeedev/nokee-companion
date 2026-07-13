package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.Collection;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * Matchers over the ABI model. The model exposes each exported symbol only as an opaque
 * {@link HashCode}, so a "symbol" matcher recomputes the symbol's hash the same way the readers do
 * and checks for its presence in the model's symbol set.
 */
final class AbiMatchers {
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;

	private AbiMatchers() {}

	static Matcher<AbiModel> sharedLibrary(Matcher<? super Collection<HashCode>> symbolsMatcher) {
		return new TypeSafeMatcher<AbiModel>() {
			@Override
			protected boolean matchesSafely(AbiModel model) {
				if (!(model instanceof SharedLibraryAbiModel)) return false;
				return symbolsMatcher.matches(((SharedLibraryAbiModel) model).getExportedSymbols());
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
					description.appendText("exported symbols ");
					symbolsMatcher.describeMismatch(((SharedLibraryAbiModel) model).getExportedSymbols(), description);
				}
			}
		};
	}

	static Matcher<AbiModel> emptySharedLibrary() {
		return sharedLibrary(empty());
	}

	static Matcher<HashCode> strongElfSymbol(String name) {
		return equalTo(elfSymbolHash(name, STB_GLOBAL));
	}

	static Matcher<HashCode> weakElfSymbol(String name) {
		return equalTo(elfSymbolHash(name, STB_WEAK));
	}

	private static HashCode elfSymbolHash(String name, int binding) {
		PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
		hasher.putString(name);
		hasher.putInt(binding);
		return hasher.hash();
	}

	static Matcher<HashCode> strongMachOSymbol(String name) {
		return equalTo(machOSymbolHash(name, false));
	}

	static Matcher<HashCode> weakMachOSymbol(String name) {
		return equalTo(machOSymbolHash(name, true));
	}

	private static HashCode machOSymbolHash(String name, boolean weak) {
		PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
		hasher.putString(name);
		hasher.putBoolean(weak);
		return hasher.hash();
	}

	static Matcher<HashCode> namedPeSymbol(String name) {
		PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
		hasher.putString(name);
		return equalTo(hasher.hash());
	}

	static Matcher<HashCode> ordinalOnlyPeSymbol(int ordinal) {
		return namedPeSymbol("#" + ordinal);
	}
}
