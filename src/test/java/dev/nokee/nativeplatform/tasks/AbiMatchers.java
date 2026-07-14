package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.Arrays;

import static org.hamcrest.Matchers.*;

/**
 * Matchers over the ABI model. The model exposes each exported symbol only as an opaque
 * {@link HashCode}, so a "symbol" matcher recomputes the symbol's hash the same way the readers do
 * and checks for its presence in the model's symbol set.
 */
final class AbiMatchers {
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;

	private AbiMatchers() {}

	static Matcher<AbiBinaryHasher.AbiBinaryHashCode> sharedLibrary(HashFunction... hashes) {
		PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
		Arrays.stream(hashes).forEach(it -> it.append(hasher));
		return sharedLibrary(equalTo(hasher.hash()));
	}

	static Matcher<AbiBinaryHasher.AbiBinaryHashCode> sharedLibrary(Matcher<? super HashCode> symbolsMatcher) {
		return new TypeSafeMatcher<>() {
			@Override
			protected boolean matchesSafely(AbiBinaryHasher.AbiBinaryHashCode model) {
				if (!(model instanceof SharedLibraryAbiModel)) return false;
				return symbolsMatcher.matches(((SharedLibraryAbiModel) model).getExportedSymbols());
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("shared library with exported symbols ").appendDescriptionOf(symbolsMatcher);
			}

			@Override
			protected void describeMismatchSafely(AbiBinaryHasher.AbiBinaryHashCode model, Description description) {
				if (!(model instanceof SharedLibraryAbiModel)) {
					description.appendText("was ").appendValue(model.getClass().getSimpleName());
				} else {
					description.appendText("exported symbols ");
					symbolsMatcher.describeMismatch(((SharedLibraryAbiModel) model).getExportedSymbols(), description);
				}
			}
		};
	}

	static Matcher<AbiBinaryHasher.AbiBinaryHashCode> emptySharedLibrary() {
		return sharedLibrary(nullValue());
	}

	static HashFunction strongElfSymbol(String name) {
		return elfSymbolHash(name, STB_GLOBAL);
	}

	static HashFunction weakElfSymbol(String name) {
		return elfSymbolHash(name, STB_WEAK);
	}

	private static HashFunction elfSymbolHash(String name, int binding) {
		return hasher -> {
			hasher.putString(name);
			hasher.putInt(binding);
		};
	}

	static HashFunction strongMachOSymbol(String name) {
		return machOSymbolHash(name, false);
	}

	static HashFunction weakMachOSymbol(String name) {
		return machOSymbolHash(name, true);
	}

	private static HashFunction machOSymbolHash(String name, boolean weak) {
		return hasher -> {
			hasher.putString(name);
			hasher.putBoolean(weak);
		};
	}

	static HashFunction namedPeSymbol(String name) {
		return hasher -> hasher.putString(name);
	}

	static HashFunction ordinalOnlyPeSymbol(int ordinal) {
		return namedPeSymbol("#" + ordinal);
	}

	interface HashFunction {
		void append(PrimitiveHasher hasher);
	}
}
