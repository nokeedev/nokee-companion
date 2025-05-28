package dev.nokee.companion.features;

import dev.nokee.companion.ShadowProperty;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.Callable;

import static dev.nokee.commons.hamcrest.Has.has;
import static dev.nokee.commons.hamcrest.gradle.BuildDependenciesMatcher.buildDependencies;
import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ShadowPropertyIntegrationTests {

	static ObjectFactory objectFactory() {
		return ProjectBuilder.builder().build().getObjects();
	}

	static ProviderFactory providerFactory() {
		return ProjectBuilder.builder().build().getProviders();
	}

	MyObject object = objectFactory().newInstance(MyObject.class);
	ShadowProperty<String> subject = new ShadowProperty<>(object, "value", object::getValue);

	@Test
	void byDefaultReturnsValueFromOriginalGetter() {
		assertThat(subject.get(), is("real-value"));
	}

	@Test
	void returnsExtraPropertyWhenExists() {
		((ExtensionAware) object).getExtensions().getExtraProperties().set("value", "replaced-value");
		assertThat(subject.get(), is("replaced-value"));
	}

	@Test
	void returnsDefaultValueWhenExtraPropertyIsNull() {
		((ExtensionAware) object).getExtensions().getExtraProperties().set("value", null);
		assertThat(subject.get(), is("real-value"));
	}

	@Test
	void mutatesDefaultValueWhenExtraPropertyIsNull() {
		((ExtensionAware) object).getExtensions().getExtraProperties().set("value", null);
		assertThat(subject.mut(it -> it + "-mutated").get(), is("real-value-mutated"));
	}

	@Test
	void setExtraPropertyWhenSettingShadowValue() {
		subject.set("another-value");
		assertThat(((ExtensionAware) object).getExtensions().getExtraProperties().get("value"), is("another-value"));
		assertThat(subject.get(), is("another-value"));
	}

	@Test
	void setExtraPropertyWhenSettingShadowValueWithProvider() {
		subject.set(providerFactory().provider(() -> "another-value"));
		assertThat(((ExtensionAware) object).getExtensions().getExtraProperties().get("value"), isA(Provider.class));
		assertThat(subject.get(), is("another-value"));
	}

	@Test
	void canMutateDefaultValue() {
		assertThat(subject.mut(it -> it + "-mutated"), is(subject));
		assertThat(((ExtensionAware) object).getExtensions().getExtraProperties().get("value"), is("real-value-mutated"));
	}

	@Test
	void canSetExtraPropertyToProvider() {
		((ExtensionAware) object).getExtensions().getExtraProperties().set("value", providerFactory().provider(() -> "provided-value"));
		assertThat(subject.get(), is("provided-value"));
	}

	@Test
	void canMutatedExtraPropertyProvider() {
		Provider<String> provider = providerFactory().provider(new Callable<String>() {
			private int count = 0;

			@Override
			public String call() throws Exception {
				return "provided-value-" + (count++);
			}
		});
		((ExtensionAware) object).getExtensions().getExtraProperties().set("value", provider);

		assertThat(subject.mut(it -> it + "-mutated"), is(subject));
		assertThat(((ExtensionAware) object).getExtensions().getExtraProperties().get("value"), isA(Provider.class));
		assertThat(subject.get(), is("provided-value-0-mutated"));
	}

	@Test
	void canMutatedExtraPropertyProvider(@TempDir File testDirectory) {
		Project project = ProjectBuilder.builder().withProjectDir(testDirectory).build();
		Provider<String> provider = project.getTasks().register("test").map(Task::getName);
		((ExtensionAware) object).getExtensions().getExtraProperties().set("value", provider);

		assertThat(project.getObjects().fileCollection().from(subject), has(buildDependencies(contains(named("test")))));
	}

	public static class MyObject {
		private final String value = "real-value";

		public String getValue() {
			return value;
		}
	}
}
