package dev.nokee.companion.features;

import dev.nokee.commons.gradle.tasks.options.ISourceKey;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.workers.WorkQueue;

import java.io.File;
import java.util.*;

final class PerSourceCompiler<T extends NativeCompileSpec> implements Compiler<T> {
	private static final Logger LOGGER = Logging.getLogger(PerSourceCompiler.class);
	private final Compiler<T> delegateCompiler;
	private final SpecProvider specProvider;
	private final CompileSpecFactory<T> specFactory;
	private final SpecConfigure<T> specConfigurer;
	private final WorkQueue queue;

	public PerSourceCompiler(Compiler<T> delegateCompiler, SpecProvider specProvider, CompileSpecFactory<T> specFactory, SpecConfigure<T> specConfigurer, WorkQueue queue) {
		this.delegateCompiler = delegateCompiler;
		this.specProvider = specProvider;
		this.specFactory = specFactory;
		this.specConfigurer = specConfigurer;
		this.queue = queue;
	}

	@Override
	public WorkResult execute(T defaultSpec) {
		assert defaultSpec.getSourceFilesForPch().isEmpty() : "not tested, hence failing";
		WorkResult result = WorkResults.didWork(false);

		// Extract the source files to recompile (under incremental scenario, only the recompile files will be available)
		List<File> sourceFiles = new ArrayList<>(defaultSpec.getSourceFiles());
		defaultSpec.setSourceFiles(Collections.emptyList()); // reset the default bucket source files

		// Build the bucket source collections
		Map<ISourceKey, Collection<File>> perSourceSpecs = new LinkedHashMap<>();
		for (File sourceFile : sourceFiles) {
			ISourceKey k = specProvider.forFile(sourceFile);
			if (k == ISourceKey.DEFAULT_KEY) { // if default bucket
				defaultSpec.getSourceFiles().add(sourceFile);
			} else { // else another bucket
				perSourceSpecs.computeIfAbsent(k, __ -> new ArrayList<>()).add(sourceFile);
			}
		}

		// Execute the default bucket
		//   it will delete the "file to remove" while the per-source bucket will only compile
		LOGGER.debug("Executing default bucket");
		result = result.or(delegateCompiler.execute(defaultSpec));

		// Execute each per-source bucket
		for (Map.Entry<ISourceKey, Collection<File>> entry : perSourceSpecs.entrySet()) {
			T newSpec = copyFrom(defaultSpec);

			// Configure per-source spec
			specConfigurer.configureSpec(newSpec, entry.getKey());

			newSpec.setSourceFiles(entry.getValue()); // set only the bucket source
			newSpec.setRemovedSourceFiles(Collections.emptyList()); // do not remove any files

			// Execute all new spec (i.e. per-source bucket)
			LOGGER.debug("Executing bucket " + entry.getKey());
			result = result.or(delegateCompiler.execute(newSpec));
		}

		// TODO: Unpack the "multiple failures and show a similar exception as before
		queue.await(); // force wait on the queue to make sure the operation logger contract is kept
		// The queue may or may not be empty, ideally, we wouldn't wait but the native compiler infrastructure is clunky.

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
		ISourceKey forFile(File file);
	}

	public interface CompileSpecFactory<T extends NativeCompileSpec> {
		T create();
	}

	public interface SpecConfigure<T extends NativeCompileSpec> {
		void configureSpec(T spec, ISourceKey key);
	}
}
