package dev.nokee.companion.features;

import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.workers.WorkQueue;

import java.io.File;
import java.util.Collection;

final class PerSourceCompiler<T extends NativeCompileSpec> implements Compiler<T> {
	private final Compiler<T> delegateCompiler;
	private final SourceSpecProvider<T> sourceSpecs;
	private final WorkQueue queue;

	public PerSourceCompiler(Compiler<T> delegateCompiler, SourceSpecProvider<T> sourceSpecs, WorkQueue queue) {
		this.delegateCompiler = delegateCompiler;
		this.sourceSpecs = sourceSpecs;
		this.queue = queue;
	}

	@Override
	public WorkResult execute(T defaultSpec) {
		assert defaultSpec.getSourceFilesForPch().isEmpty() : "not tested, hence failing";
		WorkResult result = WorkResults.didWork(false);

		// Adjust default spec (aka remove source options files)
		Iterable<T> specs = sourceSpecs.forFiles(defaultSpec.getSourceFiles());
		for (T spec : specs) {
			defaultSpec.getSourceFiles().removeAll(spec.getSourceFiles());
		}

		// Execute the default bucket
		//   it will delete the "file to remove" while the per-source bucket will only compile
		result = result.or(delegateCompiler.execute(defaultSpec));

		// Execute each per-source bucket
		for (T newSpec : specs) {
			result = result.or(delegateCompiler.execute(newSpec));
		}

		// TODO: Unpack the "multiple failures and show a similar exception as before
		queue.await(); // force wait on the queue to make sure the operation logger contract is kept
		// The queue may or may not be empty, ideally, we wouldn't wait but the native compiler infrastructure is clunky.

		return result;
	}

	public interface SourceSpecProvider<T extends NativeCompileSpec> {
		Iterable<T> forFiles(Collection<File> files);
	}
}
