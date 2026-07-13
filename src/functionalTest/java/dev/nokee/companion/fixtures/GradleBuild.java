package dev.nokee.companion.fixtures;

import dev.gradleplugins.buildscript.ast.expressions.Expression;
import dev.gradleplugins.buildscript.ast.expressions.MethodCallExpression;
import dev.gradleplugins.buildscript.ast.statements.Statement;
import dev.gradleplugins.buildscript.io.GradleBuildFile;
import dev.gradleplugins.buildscript.io.GradleSettingsFile;
import dev.gradleplugins.buildscript.syntax.Syntax;
import org.gradle.api.Action;
import org.junit.jupiter.api.function.ThrowingConsumer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

public class GradleBuild {
	private final Map<String, GradleProject> subprojects = new HashMap<>();
	private final Path location;
	private final GradleSettingsFile settingsFile;
	private GradleBuildFile buildFile = null;

	private GradleBuild(Path location) {
		this.location = location;
		this.settingsFile = GradleSettingsFile.inDirectory(location);
	}

	public Path getLocation() {
		return location;
	}

	public GradleBuild subproject(String path, Consumer<? super GradleProject> action) {
		action.accept(subprojects.computeIfAbsent(path, this::newSubproject));
		return this;
	}

	// TODO: Do not return full GradleProject
	public GradleProject subproject(String path) {
		return subprojects.computeIfAbsent(path, this::newSubproject);
	}

	public GradleBuild rootProject(Consumer<? super GradleBuildFile> action) {
		if (buildFile == null) {
			buildFile = GradleBuildFile.inDirectory(location);
		}
		action.accept(buildFile);
		return this;
	}

	public GradleBuild settingsFile(Consumer<? super GradleSettingsFile> action) {
		action.accept(settingsFile);
		return this;
	}

	private GradleProject newSubproject(String path) {
		settingsFile.append(MethodCallExpression.call("include", Syntax.string(path.replace('/', ':'))));
		return new GradleProject(location.resolve(path), GradleBuildFile.inDirectory(location.resolve(path)));
	}

	public static GradleBuild inDirectory(Path location) {
		try {
			return new GradleBuild(Files.createDirectories(location));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public GradleBuild properties(Consumer<? super GradleProperties> action) {
		action.accept(GradleProperties.inDirectory(location));
		return this;
	}

	public static final class GradleProperties {
		private final Path location;

		public GradleProperties(Path location) {
			this.location = location;
		}

		public static GradleProperties inDirectory(Path location) {
			return new GradleProperties(location.resolve("gradle.properties"));
		}

		public GradleProperties put(String key, Object value) {
			Properties properties = new Properties();

			if (Files.exists(location)) {
				try (Reader reader = Files.newBufferedReader(location)) {
					properties.load(reader);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			properties.put(key, value.toString());

			try (Writer writer = Files.newBufferedWriter(location, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
				properties.store(writer, null);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			return this;
		}
	}

	public static class GradleProject {
		private final Path location;
		public final GradleBuildFile buildFile;

		public GradleProject(Path location, GradleBuildFile buildFile) {
			this.location = location;
			this.buildFile = buildFile;
		}

		public Path file(String path) {
			return getLocation().resolve(path);
		}

		public void plugins(Action<? super PluginBlock> action) {
			buildFile.plugins(it -> {
				action.execute(new PluginBlock() {
					@Override
					public PluginBlock id(String pluginId) {
						it.id(pluginId);
						return this;
					}
				});
			});
		}

		public void append(Object snippet) {
			if (snippet instanceof Expression) {
				buildFile.append((Expression) snippet);
			} else if (snippet instanceof Statement) {
				buildFile.append((Statement) snippet);
			} else {
				throw new RuntimeException("no valid");
			}
		}

		public interface PluginBlock {
			PluginBlock id(String pluginId);
		}

		public Path getLocation() {
			return location;
		}
	}
}
