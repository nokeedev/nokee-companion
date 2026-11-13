package dev.nokee.work;

import org.gradle.api.Incubating;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method returning the work plan of a task.
 *
 * <p>The annotated method takes no parameter and returns a {@link org.gradle.api.provider.Provider} of a single work item or of a collection of work items.
 * The task type defines its work item type.</p>
 *
 * <p>The plan lists every work item of the task, regardless of its incremental or up-to-date state.
 * Querying the provider does not execute the task.
 * The provider carries the build dependencies of the task inputs, but not of the task itself.</p>
 */
@Incubating
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WorkPlan {}
