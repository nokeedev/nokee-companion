package dev.nokee.nativeplatform.tasks.internal;

import dev.nokee.work.WorkCompletionListener;
import org.gradle.api.Task;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Optional;

import javax.annotation.Nullable;

public interface WorkListenerAware extends Task {
	@ServiceReference(WorkCompletionListener.SERVICE_NAME)
	@Optional
	@SuppressWarnings("rawtypes") Property<BuildService> getWorkListenerService();

	default @Nullable WorkCompletionListener workListener() {
		return WorkListenerSupport.asListener(getPath(), getWorkListenerService());
	}
}
