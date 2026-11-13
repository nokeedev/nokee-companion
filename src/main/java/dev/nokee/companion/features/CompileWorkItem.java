package dev.nokee.companion.features;

import dev.nokee.language.cpp.tasks.CppCompile;
import dev.nokee.nativeplatform.tasks.internal.ToolInvocationWorkItem;
import org.gradle.nativeplatform.toolchain.internal.CommandLineToolInvocation;

final class CompileWorkItem extends ToolInvocationWorkItem implements CppCompile.WorkItem {
	CompileWorkItem(String executable, CommandLineToolInvocation invocation) {
		super(executable, invocation);
	}
}
