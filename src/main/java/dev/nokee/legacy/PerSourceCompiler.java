package dev.nokee.legacy;

import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;

import java.io.File;
import java.util.*;

final class PerSourceCompiler<T extends NativeCompileSpec> implements Compiler<T> {
	private final Compiler<T> delegateCompiler;
	private final SpecProvider specProvider;
	private final CompileSpecFactory<T> specFactory;

	public PerSourceCompiler(Compiler<T> delegateCompiler, SpecProvider specProvider, CompileSpecFactory<T> specFactory) {
		this.delegateCompiler = delegateCompiler;
		this.specProvider = specProvider;
		this.specFactory = specFactory;
	}

	@Override
	public WorkResult execute(T defaultSpec) {
		assert defaultSpec.getSourceFilesForPch().isEmpty() : "not tested, hence failing";
		WorkResult result = WorkResults.didWork(false);

		// Extract the source files to recompile (under incremental scenario, only the recompile files will be available)
		List<File> sourceFiles = new ArrayList<>(defaultSpec.getSourceFiles());
		defaultSpec.setSourceFiles(Collections.emptyList()); // reset the default bucket source files

		// Build the bucket source collections
		Map<CppCompileTask.AllSourceOptions<CppCompileTask.CompileOptions>.Key, Collection<File>> perSourceSpecs = new LinkedHashMap<>();
		for (File sourceFile : sourceFiles) {
			CppCompileTask.AllSourceOptions<CppCompileTask.CompileOptions>.Key k = specProvider.forFile(sourceFile);
			if (k == null) { // if default bucket
				defaultSpec.getSourceFiles().add(sourceFile);
			} else { // else another bucket
				perSourceSpecs.computeIfAbsent(k, __ -> new ArrayList<>()).add(sourceFile);
			}
		}

		// TODO: Align the OperationLogger so one start then one done for all sub-spec
		int expectedRuns = perSourceSpecs.size() + ((!defaultSpec.getSourceFiles().isEmpty() || !defaultSpec.getRemovedSourceFiles().isEmpty()) ? 1 : 0);
		BuildOperationLogger logger = new BuildOperationLogger() {
			private int i = 0;
			private final BuildOperationLogger delegate = defaultSpec.getOperationLogger();

			@Override
			public void start() {
				if (i++ == 0) {
					delegate.start();
				}
			}

			@Override
			public void operationSuccess(String description, String output) {
				delegate.operationSuccess(description, output);
			}

			@Override
			public void operationFailed(String description, String output) {
				delegate.operationFailed(description, output);
			}

			@Override
			public void done() {
				if (i == expectedRuns) {
					delegate.done();
				}
			}

			@Override
			public String getLogLocation() {
				return delegate.getLogLocation();
			}
		};
		defaultSpec.setOperationLogger(logger);

		// Execute the default bucket
		//   it will delete the "file to remove" while the per-source bucket will only compile
		result = result.or(delegateCompiler.execute(defaultSpec));

		// Execute each per-source bucket
		for (Map.Entry<CppCompileTask.AllSourceOptions<CppCompileTask.CompileOptions>.Key, Collection<File>> entry : perSourceSpecs.entrySet()) {
			T newSpec = copyFrom(defaultSpec);
			newSpec.setSourceFiles(entry.getValue()); // set only the bucket source
			newSpec.setRemovedSourceFiles(Collections.emptyList()); // do not remove any files

			// Namespace the temporary directory (i.e. where the options.txt will be written)
			newSpec.setTempDir(new File(newSpec.getTempDir(), String.valueOf(entry.getKey().hashCode())));

			// Configure the bucket spec from the per-source options
			newSpec.args(entry.getKey().get().getCompilerArgs().get());

			// Execute all new spec (i.e. per-source bucket)
			result = result.or(delegateCompiler.execute(newSpec));
		}

		return result;
	}

	// Hand rolled implementation
	private T copyFrom(T spec) {
		T result = specFactory.create();
		result.setTargetPlatform(spec.getTargetPlatform());
		result.setTempDir(spec.getTempDir());
		result.getArgs().addAll(spec.getArgs());
		result.getSystemArgs().addAll(spec.getSystemArgs());
		result.setOperationLogger(spec.getOperationLogger());

		result.setObjectFileDir(spec.getObjectFileDir());
		result.getIncludeRoots().addAll(spec.getIncludeRoots());
		result.getSystemIncludeRoots().addAll(spec.getSystemIncludeRoots());
		result.setSourceFiles(spec.getSourceFiles());
		result.setRemovedSourceFiles(spec.getRemovedSourceFiles());
		result.setMacros(spec.getMacros());
		result.setPositionIndependentCode(spec.isPositionIndependentCode());
		result.setDebuggable(spec.isDebuggable());
		result.setOptimized(spec.isOptimized());
		result.setIncrementalCompile(spec.isIncrementalCompile());
		result.setPrefixHeaderFile(spec.getPrefixHeaderFile());
		result.setPreCompiledHeaderObjectFile(spec.getPreCompiledHeaderObjectFile());
		result.setPreCompiledHeader(spec.getPreCompiledHeader());
		result.setSourceFilesForPch(spec.getSourceFilesForPch());

		return result;
	}

	public interface SpecProvider {
		CppCompileTask.AllSourceOptions<CppCompileTask.CompileOptions>.Key forFile(File file);
	}

	public interface CompileSpecFactory<T extends NativeCompileSpec> {
		T create();
	}
}
