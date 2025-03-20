package dev.nokee.companion.features;

import dev.nokee.commons.fixtures.Subject;
import dev.nokee.commons.fixtures.SubjectExtension;
import dev.nokee.language.nativebase.tasks.options.PreprocessorOptions;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith(SubjectExtension.class)
public abstract class MacroTester {
	protected interface Macros extends Iterable<Macros.Entry> {
		Set<String> definedMacros();

		Macros define(String name);
		Macros define(String name, String definition);

		void remove(String name);

		void clear();

		default Map<String, String> asMap() {
			final Map<String, String> result = new LinkedHashMap<>();
			forEach(it -> result.put(it.name(), it.definition()));
			return result;
		}

		default @Nullable String get(String name) {
			return asMap().get(name);
		}

		record Entry(String name, @Nullable String definition) {
			private static Entry newInstance(Map.Entry<String, String> entry) {
				return new Entry(entry.getKey(), entry.getValue());
			}
		}
	}

	protected final Macros newMacros(AbstractNativeCompileTask task) {
		return new Macros() {
			@Override
			public Set<String> definedMacros() {
				return task.getMacros().keySet();
			}

			@Override
			public Macros define(String name) {
				task.getMacros().put(name, null);
				return this;
			}

			@Override
			public Macros define(String name, String definition) {
				task.getMacros().put(name, definition);
				return this;
			}

			@Override
			public void remove(String name) {
				task.getMacros().remove(name);
			}

			@Override
			public void clear() {
				task.getMacros().clear();
			}

			@Override
			public Iterator<Entry> iterator() {
				return task.getMacros().entrySet().stream().map(Entry::newInstance).iterator();
			}
		};
	}

	protected final Macros newMacros(PreprocessorOptions options) {
		return new Macros() {
			@Override
			public Set<String> definedMacros() {
				return options.getDefinedMacros().get().stream().map(PreprocessorOptions.DefinedMacro::getName).collect(Collectors.toSet());
			}

			@Override
			public Macros define(String name) {
				options.define(name);
				return this;
			}

			@Override
			public Macros define(String name, String definition) {
				options.define(name, definition);
				return this;
			}

			@Override
			public void remove(String name) {
				Assumptions.abort("not supported");
			}

			@Override
			public void clear() {
				options.getDefinedMacros().empty();
			}

			@Override
			public Iterator<Entry> iterator() {
				return options.getDefinedMacros().get().stream().map(it -> new Entry(it.getName(), it.getDefinition())).iterator();
			}
		};
	}

	@Test
	void canDefineMacroWithoutDefinition(@Subject Macros subject) {
		assertThat(subject.definedMacros(), everyItem(not(equalTo("MACRO1"))));

		subject.define("MACRO1");

		assertThat(subject.definedMacros(), hasItem("MACRO1"));
		assertThat(subject.get("MACRO1"), nullValue());
	}

	@Test
	void canDefineMacroWithDefinition(@Subject Macros subject) {
		assertThat(subject.definedMacros(), everyItem(not(equalTo("MACRO1"))));

		subject.define("MACRO1", "val1");

		assertThat(subject.definedMacros(), hasItem("MACRO1"));
		assertThat(subject.get("MACRO1"), equalTo("val1"));
	}

	@Test
	void canOverwriteDefinedMacro(@Subject Macros subject) {
		assertThat(subject.definedMacros(), everyItem(not(equalTo("MACRO"))));

		subject.define("MACRO");
		assertThat(subject.get("MACRO"), nullValue());

		subject.define("MACRO", "some-val");
		assertThat(subject.get("MACRO"), equalTo("some-val"));
	}

	@Test
	void canRemoveMacros(@Subject Macros subject) {
		assertThat(subject.definedMacros(), everyItem(not(anyOf(equalTo("MACRO1"), equalTo("MACRO2")))));

		subject.define("MACRO1");
		subject.define("MACRO2", "val2");
		assertThat(subject.definedMacros(), hasItems("MACRO1", "MACRO2"));

		subject.remove("MACRO1");
		assertThat(subject.definedMacros(), not(hasItem("MACRO1")));

		subject.remove("MACRO2");
		assertThat(subject.definedMacros(), not(hasItem("MACRO2")));
	}

	@Test
	void canClearDefinedMacros(@Subject("with-macros") Macros subject) {
		assertThat(subject.definedMacros(), not(empty()));

		subject.clear();
		assertThat(subject.definedMacros(), empty());
	}
}
