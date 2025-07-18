/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.nokee.companion.features;

import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.TaskOutputsInternal;
import org.gradle.api.internal.file.TaskFileVarFactory;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.api.internal.file.collections.MinimalFileSet;
import org.gradle.api.internal.tasks.properties.LifecycleAwareValue;
import org.gradle.api.provider.Provider;
import org.gradle.cache.ObjectHolder;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.operations.BuildOperationRunner;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.nativeplatform.internal.*;
import org.gradle.language.nativeplatform.internal.incremental.*;
import org.gradle.language.nativeplatform.internal.incremental.sourceparser.CSourceParser;
import org.gradle.language.nativeplatform.internal.incremental.sourceparser.MacroWithSimpleExpression;
import org.gradle.language.nativeplatform.internal.incremental.sourceparser.RegexBackedCSourceParser;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

class DefaultIncrementalCompilerBuilder implements IncrementalCompilerBuilder {
	private static final Object CURRENT_STATE_VERSION = "v1";
	private final BuildOperationRunner buildOperationRunner;
	private final CompilationStateCacheFactory compilationStateCacheFactory;
	private final CSourceParser sourceParser;
	private final Deleter deleter;
	private final DirectoryFileTreeFactory directoryFileTreeFactory;
	private final FileSystemAccess fileSystemAccess;
	private final TaskFileVarFactory fileVarFactory;

	public DefaultIncrementalCompilerBuilder(
		BuildOperationRunner buildOperationRunner,
		CompilationStateCacheFactory compilationStateCacheFactory,
		CSourceParser sourceParser,
		Deleter deleter,
		DirectoryFileTreeFactory directoryFileTreeFactory,
		FileSystemAccess fileSystemAccess,
		TaskFileVarFactory fileVarFactory
	) {
		this.buildOperationRunner = buildOperationRunner;
		this.compilationStateCacheFactory = compilationStateCacheFactory;
		this.deleter = deleter;
		this.directoryFileTreeFactory = directoryFileTreeFactory;
		this.fileSystemAccess = fileSystemAccess;
		this.fileVarFactory = fileVarFactory;
		this.sourceParser = sourceParser;
	}

	@Override
	public IncrementalCompiler newCompiler(TaskInternal task, FileCollection sourceFiles, FileCollection includeDirs, Map<String, String> macros, Provider<Boolean> importAware) {
		return new StateCollectingIncrementalCompiler(
			task,
			includeDirs,
			sourceFiles,
			macros,
			importAware,
			buildOperationRunner,
			compilationStateCacheFactory,
			sourceParser,
			deleter,
			directoryFileTreeFactory,
			fileSystemAccess,
			fileVarFactory
		);
	}

	Object currentStateVersion() {
		return CURRENT_STATE_VERSION;
	}

	private static class StateCollectingIncrementalCompiler implements IncrementalCompiler, MinimalFileSet, LifecycleAwareValue {
		private final BuildOperationRunner buildOperationRunner;
		private final CompilationStateCacheFactory compilationStateCacheFactory;
		private final CSourceParser sourceParser;
		private final Deleter deleter;
		private final DirectoryFileTreeFactory directoryFileTreeFactory;
		private final FileSystemAccess fileSystemAccess;

		private final Map<String, String> macros;
		private final Provider<Boolean> importAware;
		private final TaskOutputsInternal taskOutputs;
		private final FileCollection includeDirs;
		private final String taskPath;
		private final FileCollection sourceFiles;
		private final FileCollection headerFilesCollection;
		private Object/*Holder<CompilationState>*/ compileStateCache;
		private IncrementalCompilation incrementalCompilation;

		StateCollectingIncrementalCompiler(
			TaskInternal task,
			FileCollection includeDirs,
			FileCollection sourceFiles,
			Map<String, String> macros,
			Provider<Boolean> importAware,

			BuildOperationRunner buildOperationRunner,
			CompilationStateCacheFactory compilationStateCacheFactory,
			CSourceParser sourceParser,
			Deleter deleter,
			DirectoryFileTreeFactory directoryFileTreeFactory,
			FileSystemAccess fileSystemAccess,
			TaskFileVarFactory fileVarFactory
		) {
			this.taskOutputs = task.getOutputs();
			this.taskPath = task.getPath();
			this.includeDirs = includeDirs;
			this.sourceFiles = sourceFiles;
			this.macros = macros;
			this.importAware = importAware;
			this.headerFilesCollection = fileVarFactory.newCalculatedInputFileCollection(task, this, sourceFiles, includeDirs);

			this.buildOperationRunner = buildOperationRunner;
			this.compilationStateCacheFactory = compilationStateCacheFactory;
			this.deleter = deleter;
			this.directoryFileTreeFactory = directoryFileTreeFactory;
			this.fileSystemAccess = fileSystemAccess;
			this.sourceParser = sourceParser;
		}

		@Override
		public <T extends NativeCompileSpec> Compiler<T> createCompiler(Compiler<T> compiler) {
			if (incrementalCompilation == null) {
				throw new IllegalStateException("Header files should be calculated before compiler is created.");
			}
			try {
				@SuppressWarnings("unchecked")
				IncrementalNativeCompiler<T> result = (IncrementalNativeCompiler<T>) IncrementalNativeCompiler.class.getConstructors()[0].newInstance(taskOutputs, compiler, deleter, compileStateCache, incrementalCompilation);
				return result;
			} catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}

		private static Object/*Holder<CompilationState>*/ CompilationStateCacheFactory__create(CompilationStateCacheFactory self, String taskPath) {
			try {
				Method create = self.getClass().getMethod("create", String.class);
				return create.invoke(self, taskPath);
			} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public Set<File> getFiles() {
			List<File> includeRoots = new ArrayList<>(includeDirs.getFiles());
			compileStateCache = CompilationStateCacheFactory__create(compilationStateCacheFactory, ":v1" + taskPath);
			DefaultSourceIncludesParser sourceIncludesParser = new DefaultSourceIncludesParser(sourceParser, importAware.get());
			DefaultSourceIncludesResolver dependencyParser = new DefaultSourceIncludesResolver(includeRoots, fileSystemAccess);
			IncludeDirectives includeDirectives = directivesForMacros(macros);
			IncrementalCompileFilesFactory incrementalCompileFilesFactory = new IncrementalCompileFilesFactory(includeDirectives, sourceIncludesParser, dependencyParser, fileSystemAccess);
			IncrementalCompileProcessor incrementalCompileProcessor = new IncrementalCompileProcessor(compileStateCache, incrementalCompileFilesFactory, buildOperationRunner);

			incrementalCompilation = incrementalCompileProcessor.processSourceFiles(new TreeSet<>(sourceFiles.getFiles()));
			DefaultHeaderDependenciesCollector headerDependenciesCollector = new DefaultHeaderDependenciesCollector(directoryFileTreeFactory);
			return collectExistingHeaderDependencies(headerDependenciesCollector, taskPath, includeRoots, incrementalCompilation);
		}

		private static Set<File> collectExistingHeaderDependencies(HeaderDependenciesCollector self, String taskPath, List<File> includeRoots, IncrementalCompilation incrementalCompilation) {
			try {
				Method HeaderDependenciesCollector__collectExistingHeaderDependencies = HeaderDependenciesCollector.class.getMethod("collectExistingHeaderDependencies", String.class, List.class, IncrementalCompilation.class);
				@SuppressWarnings("unchecked")
				Set<File> result = (Set<File>) HeaderDependenciesCollector__collectExistingHeaderDependencies.invoke(self, taskPath, includeRoots, incrementalCompilation);
				return result;
			} catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}

		private IncludeDirectives directivesForMacros(Map<String, String> macros) {
			List<Macro> values = new ArrayList<>();
			for(Map.Entry<String, String> entry : macros.entrySet()) {
				Expression expression = RegexBackedCSourceParser.parseExpression(entry.getValue());
				values.add(new MacroWithSimpleExpression(entry.getKey(), expression.getType(), expression.getValue()));
			}
			return new IncludeDirectives() {
				@Override
				public List<Include> getQuotedIncludes() {
					return Collections.emptyList();
				}

				@Override
				public List<Include> getSystemIncludes() {
					return Collections.emptyList();
				}

				@Override
				public List<Include> getMacroIncludes() {
					return Collections.emptyList();
				}

				@Override
				public List<Include> getAll() {
					return Collections.emptyList();
				}

				@Override
				public List<Include> getIncludesOnly() {
					return Collections.emptyList();
				}

				@Override
				public Iterable<Macro> getMacros(String name) {
					return values.stream().filter(it -> it.getName().equals(name)).collect(Collectors.toList());
				}

				@Override
				public Iterable<MacroFunction> getMacroFunctions(String name) {
					return Collections.emptyList();
				}

				@Override
				public Collection<Macro> getAllMacros() {
					return values;
				}

				@Override
				public Collection<MacroFunction> getAllMacroFunctions() {
					return Collections.emptyList();
				}

				@Override
				public boolean hasMacros() {
					return !values.isEmpty();
				}

				@Override
				public boolean hasMacroFunctions() {
					return false; // only simple macros
				}

				@Override
				public IncludeDirectives discardImports() {
					return this; // no includes
				}
			};
		}

		@Override
		public void prepareValue() {
		}

		@Override
		public void cleanupValue() {
			compileStateCache = null;
			incrementalCompilation = null;
		}

		@Override
		public String getDisplayName() {
			return "header files for " + taskPath;
		}

		@Override
		public FileCollection getHeaderFiles() {
			return headerFilesCollection;
		}
	}
}
