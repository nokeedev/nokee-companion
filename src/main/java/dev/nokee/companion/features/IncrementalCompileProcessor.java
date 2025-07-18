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

import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationRunner;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.language.nativeplatform.internal.incremental.CompilationState;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilation;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompileSourceProcessor;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;

class IncrementalCompileProcessor {
	private final Object/*Holder<CompilationState>*/ previousCompileStateCache;
	private final IncrementalCompileFilesFactory incrementalCompileFilesFactory;
	private final BuildOperationRunner buildOperationExecutor;

	public IncrementalCompileProcessor(Object/*Holder<CompilationState>*/ previousCompileStateCache, IncrementalCompileFilesFactory incrementalCompileFilesFactory, BuildOperationRunner buildOperationExecutor) {
		this.previousCompileStateCache = previousCompileStateCache;
		this.incrementalCompileFilesFactory = incrementalCompileFilesFactory;
		this.buildOperationExecutor = buildOperationExecutor;
	}

	private static <T> T ObjectHolder__get(Object obj) {
		try {
			Method get = obj.getClass().getInterfaces()[0].getMethod("get");
			@SuppressWarnings("unchecked")
			T result = (T) get.invoke(obj);
			return result;
		} catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public IncrementalCompilation processSourceFiles(final Collection<File> sourceFiles) {
		return buildOperationExecutor.call(new CallableBuildOperation<IncrementalCompilation>() {
			@Override
			public IncrementalCompilation call(BuildOperationContext context) {
				CompilationState previousCompileState = ObjectHolder__get(previousCompileStateCache);
				IncrementalCompileSourceProcessor processor = incrementalCompileFilesFactory.files(previousCompileState);
				for (File sourceFile : sourceFiles) {
					processor.processSource(sourceFile);
				}
				return processor.getResult();
			}

			@Override
			public BuildOperationDescriptor.Builder description() {
				ProcessSourceFilesDetails operationDetails = new ProcessSourceFilesDetails(sourceFiles.size());
				return BuildOperationDescriptor
					.displayName("Processing source files")
					.details(operationDetails);
			}

			class ProcessSourceFilesDetails {
				private final int sourceFileCount;

				ProcessSourceFilesDetails(int sourceFileCount) {
					this.sourceFileCount = sourceFileCount;
				}

				public int getSourceFileCount() {
					return sourceFileCount;
				}
			}
		});
	}
}
