package dev.nokee.nativeplatform.tasks.internal;

import org.gradle.api.Action;
import org.gradle.internal.operations.*;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.CompileSpec;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.nativeplatform.toolchain.internal.AbstractCompiler;
import org.gradle.nativeplatform.toolchain.internal.CommandLineToolInvocation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import static dev.nokee.companion.features.ReflectionUtils.*;

public final class WorkPlanSupport {
	private WorkPlanSupport() {}

	public static final class Recorder<T> {
		private final BiFunction<String, CommandLineToolInvocation, T> itemFactory;
		private final List<T> items = new ArrayList<>();

		public Recorder(BiFunction<String, CommandLineToolInvocation, T> itemFactory) {
			this.itemFactory = itemFactory;
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		public <S extends CompileSpec> void capture(Compiler<S> compiler, S spec) {
			AbstractCompiler tool = WorkListenerSupport.unwrap(compiler);
			if (tool == null) {
				throw new IllegalStateException(String.format("Could not compute the work plan, no native tool found in '%s'.", compiler));
			}
			updateFieldValue(getField(AbstractCompiler.class, "buildOperationExecutor"), tool, new CapturingBuildOperationExecutor(this));
			tool.execute(spec);
		}

		private void record(String executable, CommandLineToolInvocation invocation) {
			items.add(itemFactory.apply(executable, invocation));
		}

		public List<T> getItems() {
			return Collections.unmodifiableList(new ArrayList<>(items));
		}
	}

	public static BuildOperationLogger discardingOperationLogger(File logLocation) {
		return new BuildOperationLogger() {
			@Override
			public void start() {}

			@Override
			public void operationSuccess(String description, String output) {}

			@Override
			public void operationFailed(String description, String output) {}

			@Override
			public void done() {}

			@Override
			public String getLogLocation() {
				return logLocation.getAbsolutePath();
			}
		};
	}

	private static final class CapturingBuildOperationExecutor implements BuildOperationExecutor {
		private final Recorder<?> recorder;

		private CapturingBuildOperationExecutor(Recorder<?> recorder) {
			this.recorder = recorder;
		}

		@Override
		public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> action) {
			action.execute(new CapturingBuildOperationQueue<>(WorkListenerSupport.executableOf(worker)));
		}

		@Override
		public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			runAll(worker, action);
		}

		@Override
		public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> action) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> action) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			throw new UnsupportedOperationException();
		}

		public BuildOperationRef getCurrentOperation() {
			throw new UnsupportedOperationException();
		}

		private final class CapturingBuildOperationQueue<O extends BuildOperation> implements BuildOperationQueue<O> {
			private final String executable;

			private CapturingBuildOperationQueue(String executable) {
				this.executable = executable;
			}

			@Override
			public void add(O operation) {
				recorder.record(executable, (CommandLineToolInvocation) operation);
			}

			@Override
			public void cancel() {}

			@Override
			public void waitForCompletion() {}

			@Override
			public void setLogLocation(String logLocation) {}
		}
	}
}
