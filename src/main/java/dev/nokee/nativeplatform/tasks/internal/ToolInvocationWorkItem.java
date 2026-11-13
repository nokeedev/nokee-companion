package dev.nokee.nativeplatform.tasks.internal;

import org.gradle.nativeplatform.toolchain.internal.CommandLineToolInvocation;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;

public abstract class ToolInvocationWorkItem implements Serializable {
	private final String executable;
	private final List<String> arguments;
	private final Map<String, String> files;
	@Nullable private final File workingDirectory;
	private final Map<String, String> environment;
	private final List<File> path;

	protected ToolInvocationWorkItem(String executable, CommandLineToolInvocation invocation) {
		List<String> arguments = new ArrayList<>();
		invocation.getArgs().forEach(arguments::add);
		this.executable = executable;
		this.arguments = Collections.unmodifiableList(arguments);
		this.files = Collections.unmodifiableMap(optionsFilesOf(arguments));
		this.workingDirectory = invocation.getWorkDirectory();
		this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(invocation.getEnvironment()));
		this.path = Collections.unmodifiableList(new ArrayList<>(invocation.getPath()));
	}

	private static Map<String, String> optionsFilesOf(List<String> arguments) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String argument : arguments) {
			if (argument.startsWith("@")) {
				File optionsFile = new File(argument.substring(1));
				result.put(optionsFile.getAbsolutePath(), contentOf(optionsFile));
			}
		}
		return result;
	}

	private static String contentOf(File file) {
		try {
			return new String(Files.readAllBytes(file.toPath()), Charset.defaultCharset());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public String getExecutable() {
		return executable;
	}

	public List<String> getArguments() {
		return arguments;
	}

	public Map<String, String> getFiles() {
		return files;
	}

	public @Nullable File getWorkingDirectory() {
		return workingDirectory;
	}

	public Map<String, String> getEnvironment() {
		return environment;
	}

	public List<File> getPath() {
		return path;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ToolInvocationWorkItem that = (ToolInvocationWorkItem) o;
		return executable.equals(that.executable) && arguments.equals(that.arguments) && files.equals(that.files)
			&& Objects.equals(workingDirectory, that.workingDirectory) && environment.equals(that.environment) && path.equals(that.path);
	}

	@Override
	public int hashCode() {
		return Objects.hash(getClass(), executable, arguments, files, workingDirectory, environment, path);
	}

	@Override
	public String toString() {
		return executable + " " + String.join(" ", arguments);
	}
}
