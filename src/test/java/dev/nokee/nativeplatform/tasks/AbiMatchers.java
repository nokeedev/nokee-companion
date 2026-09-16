package dev.nokee.nativeplatform.tasks;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import static dev.nokee.nativeplatform.tasks.ElfAbi.STB_GLOBAL;
import static dev.nokee.nativeplatform.tasks.ElfAbi.STB_WEAK;
import static dev.nokee.nativeplatform.tasks.ElfAbi.STT_FUNC;
import static dev.nokee.nativeplatform.tasks.ElfAbi.STT_OBJECT;
import static dev.nokee.nativeplatform.tasks.ElfAbi.STT_TLS;
import static dev.nokee.nativeplatform.tasks.MicrosoftImportObjectBlob.IMPORT_OBJECT_CODE;
import static dev.nokee.nativeplatform.tasks.MicrosoftImportObjectBlob.IMPORT_OBJECT_DATA;
import static dev.nokee.nativeplatform.tasks.MicrosoftImportObjectBlob.IMPORT_OBJECT_ORDINAL;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Matchers over what the ABI readers report about one exported symbol.
 *
 * <p>Each matcher starts from what the symbol is — a strong function, a data export — and narrows from
 * there, one field per call. A field nobody narrows goes unchecked, so an assertion about binding says
 * nothing about size, and one about size says nothing about versions.
 */
final class AbiMatchers {
	private AbiMatchers() {}

	// --- ELF ------------------------------------------------------------------------------------

	static ElfExportMatcher strongFunction(String name) {
		return new ElfExportMatcher("strong function", name, STB_GLOBAL, STT_FUNC);
	}

	static ElfExportMatcher weakFunction(String name) {
		return new ElfExportMatcher("weak function", name, STB_WEAK, STT_FUNC);
	}

	static ElfExportMatcher strongVariable(String name) {
		return new ElfExportMatcher("strong variable", name, STB_GLOBAL, STT_OBJECT);
	}

	static ElfExportMatcher weakVariable(String name) {
		return new ElfExportMatcher("weak variable", name, STB_WEAK, STT_OBJECT);
	}

	static ElfExportMatcher threadLocalVariable(String name) {
		return new ElfExportMatcher("thread-local variable", name, STB_GLOBAL, STT_TLS);
	}

	/**
	 * The symbol a version definition carries. The linker emits one at {@code SHN_ABS} for every version
	 * a library defines, and the reader reports it alongside the exports themselves.
	 */
	static ElfExportMatcher versionDefinition(String version) {
		return new ElfExportMatcher("version definition", version, STB_GLOBAL, STT_OBJECT);
	}

	static final class ElfExportMatcher extends TypeSafeDiagnosingMatcher<ElfAbi.Export> {
		private final String kind;
		private final String name;
		private final int binding;
		private final int type;
		private Matcher<? super Long> size = anything();
		private Matcher<? super String> version = anything();
		private Matcher<? super Boolean> defaultVersion = anything();
		private String narrowing = "";

		private ElfExportMatcher(String kind, String name, int binding, int type) {
			this.kind = kind;
			this.name = name;
			this.binding = binding;
			this.type = type;
		}

		/** Narrows to the size the symbol carries, which for a variable is the storage a consumer sees. */
		ElfExportMatcher ofSize(long size) {
			this.size = is(size);
			return narrowing("of size " + size);
		}

		/** Narrows to a version, and to being the definition an unversioned reference binds to. */
		ElfExportMatcher atDefaultVersion(String version) {
			this.version = is(version);
			this.defaultVersion = is(true);
			return narrowing("at default version " + version);
		}

		/** Narrows to a version only a reference naming that version binds to. */
		ElfExportMatcher atNonDefaultVersion(String version) {
			this.version = is(version);
			this.defaultVersion = is(false);
			return narrowing("at non-default version " + version);
		}

		private ElfExportMatcher narrowing(String text) {
			this.narrowing += " " + text;
			return this;
		}

		@Override
		protected boolean matchesSafely(ElfAbi.Export export, Description mismatch) {
			return check(mismatch, "name", export.name(), is(name))
				& check(mismatch, "binding", export.binding(), is(binding))
				& check(mismatch, "type", export.type(), is(type))
				& check(mismatch, "size", export.size(), size)
				& check(mismatch, "version", export.version(), version)
				& check(mismatch, "defaultVersion", export.defaultVersion(), defaultVersion);
		}

		@Override
		public void describeTo(Description description) {
			description.appendText(kind).appendText(" ").appendValue(name).appendText(narrowing);
		}
	}

	// --- Windows import library -----------------------------------------------------------------

	/** An export a consumer reaches through code the DLL defines. */
	static ImportObjectMatcher codeExport(String symbolName) {
		return new ImportObjectMatcher("code export", symbolName, IMPORT_OBJECT_CODE);
	}

	/** An export a consumer reaches as storage, reading it through the import address table. */
	static ImportObjectMatcher dataExport(String symbolName) {
		return new ImportObjectMatcher("data export", symbolName, IMPORT_OBJECT_DATA);
	}

	static final class ImportObjectMatcher extends TypeSafeDiagnosingMatcher<ImportLibraryAbi.Export> {
		private final String kind;
		private final String symbolName;
		private final int type;
		private Matcher<? super Integer> nameType = anything();
		private Matcher<? super Integer> ordinal = anything();
		private String narrowing = "";

		private ImportObjectMatcher(String kind, String symbolName, int type) {
			this.kind = kind;
			this.symbolName = symbolName;
			this.type = type;
		}

		/**
		 * Narrows to an export the DLL names, spelled the way {@code nameType} relates the symbol to the
		 * exported name — which is what tells a decorated target from an undecorated one.
		 */
		ImportObjectMatcher named(int nameType) {
			this.nameType = is(nameType);
			this.ordinal = nullValue(Integer.class);
			return narrowing("named in the export table");
		}

		/** Narrows to an export reached by ordinal alone, which is what a {@code NONAME} export is. */
		ImportObjectMatcher atOrdinal(int ordinal) {
			this.nameType = is(IMPORT_OBJECT_ORDINAL);
			this.ordinal = is(ordinal);
			return narrowing("at ordinal " + ordinal);
		}

		private ImportObjectMatcher narrowing(String text) {
			this.narrowing += " " + text;
			return this;
		}

		@Override
		protected boolean matchesSafely(ImportLibraryAbi.Export export, Description mismatch) {
			return check(mismatch, "symbolName", export.symbolName(), is(symbolName))
				& check(mismatch, "type", export.type(), is(type))
				& check(mismatch, "nameType", export.nameType(), nameType)
				& check(mismatch, "ordinal", export.ordinal(), ordinal);
		}

		@Override
		public void describeTo(Description description) {
			description.appendText(kind).appendText(" ").appendValue(symbolName).appendText(narrowing);
		}
	}

	private static <V> boolean check(Description mismatch, String field, V actual, Matcher<? super V> expected) {
		if (expected.matches(actual)) {
			return true;
		}

		mismatch.appendText(" ").appendText(field).appendText(" was ").appendValue(actual);
		return false;
	}
}
