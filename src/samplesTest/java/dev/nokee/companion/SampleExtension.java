package dev.nokee.companion;

import dev.gradleplugins.fixtures.sources.ProjectElement;
import dev.nokee.commons.sources.GradleBuildElement;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class SampleExtension implements ParameterResolver {
	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		return parameterContext.isAnnotated(Sample.class);
	}

	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		String sampleName = parameterContext.findAnnotation(Sample.class).map(Sample::value).orElseThrow();
		Path sampleLocation = Paths.get(Optional.ofNullable(System.getProperties().get(sampleName)).orElseThrow().toString());

		System.out.println(parameterContext.getParameter().getParameterizedType());

		ProjectElement project = GradleBuildElement.fromDirectory(sampleLocation);

		if (parameterContext.getParameter().getType().equals(GradleBuildElement.class)) {
			return project;
		} else if (parameterContext.getParameter().getType().equals(ExemplarElement.class)) {
			return new ExemplarElement<>(project);
		}
		throw new UnsupportedOperationException();
	}
}
