package dev.nokee.nativeplatform.tasks;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.*;

final class AbiMatchers {
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;

	private AbiMatchers() {}

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
				return symbol.getName().equals(name);
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
				return symbol.getName().equals(name)
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

	public static class MyExportedSymbol extends AbstractMap<String, Object> implements ExportedSymbol {
		private final String name;
		private final Map<String, Object> values = new HashMap<>();

		public MyExportedSymbol(String name) {
			this.name = name;
		}

		@Override
		public Object getName() {
			return name;
		}

		@Override
		public Set<Entry<String, Object>> entrySet() {
			return values.entrySet();
		}

		@Override
		public Object put(String key, Object value) {
			return values.put(key, value);
		}
	}
}
