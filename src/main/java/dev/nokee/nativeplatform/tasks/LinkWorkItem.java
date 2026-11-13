package dev.nokee.nativeplatform.tasks;

import dev.nokee.nativeplatform.tasks.internal.ToolInvocationWorkItem;
import org.gradle.nativeplatform.toolchain.internal.CommandLineToolInvocation;

final class LinkWorkItem extends ToolInvocationWorkItem implements LinkTask.WorkItem {
	LinkWorkItem(String executable, CommandLineToolInvocation invocation) {
		super(executable, invocation);
	}
}
