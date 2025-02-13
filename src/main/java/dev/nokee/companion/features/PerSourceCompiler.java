package dev.nokee.companion.features;

import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.internal.UncheckedException;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
		Map<CppCompileTask.AllSourceOptions<NativeCompileOptions>.Key, Collection<File>> perSourceSpecs = new LinkedHashMap<>();
		for (File sourceFile : sourceFiles) {
			CppCompileTask.AllSourceOptions<NativeCompileOptions>.Key k = specProvider.forFile(sourceFile);
			if (k == null) { // if default bucket
				defaultSpec.getSourceFiles().add(sourceFile);
			} else { // else another bucket
				perSourceSpecs.computeIfAbsent(k, __ -> new ArrayList<>()).add(sourceFile);
			}
		}

		// Execute the default bucket
		//   it will delete the "file to remove" while the per-source bucket will only compile
		result = result.or(delegateCompiler.execute(defaultSpec));

		// Execute each per-source bucket
		for (Map.Entry<CppCompileTask.AllSourceOptions<NativeCompileOptions>.Key, Collection<File>> entry : perSourceSpecs.entrySet()) {
			T newSpec = copyFrom(defaultSpec);
			newSpec.setSourceFiles(entry.getValue()); // set only the bucket source
			newSpec.setRemovedSourceFiles(Collections.emptyList()); // do not remove any files

			// Namespace the temporary directory (i.e. where the options.txt will be written)
			newSpec.setTempDir(new File(newSpec.getTempDir(), hash(entry.getKey())));

			// Configure the bucket spec from the per-source options
			newSpec.args(entry.getKey().get().getCompilerArgs().get());
			for (CommandLineArgumentProvider argumentProvider : entry.getKey().get().getCompilerArgumentProviders().get()) {
				argumentProvider.asArguments().forEach(newSpec.getArgs()::add);
			}

			// Execute all new spec (i.e. per-source bucket)
			result = result.or(delegateCompiler.execute(newSpec));
		}

		return result;
	}

	private String hash(CppCompileTask.AllSourceOptions<?>.Key key) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("MD5");
			for (Integer i : key) {
				messageDigest.update(ByteBuffer.allocate(4).putInt(i).array());
			}
			return new BigInteger(1, messageDigest.digest()).toString(36);
		} catch (NoSuchAlgorithmException e) {
			throw UncheckedException.throwAsUncheckedException(e);
		}
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
		CppCompileTask.AllSourceOptions<NativeCompileOptions>.Key forFile(File file);
	}

	public interface CompileSpecFactory<T extends NativeCompileSpec> {
		T create();
	}
}
