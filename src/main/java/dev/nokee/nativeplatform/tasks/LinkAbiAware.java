package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;

import javax.inject.Inject;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

interface LinkAbiAware extends Task {
	@Internal
	Property<LinkAbiExtension> getExt_linkAbi();

	@Nested
	default LinkAbiExtension getLinkAbi() {
		if (!getExt_linkAbi().isPresent()) { // safe as we control the lifecycle
			assert this instanceof AbstractLinkTask : "task must be an AbstractLinkTask";
			FileCollection libs = ((AbstractLinkTask) this).getLibs();
			getExt_linkAbi().set(getProject().getObjects().newInstance(LinkAbiExtension.class, libs));
		}

		return getExt_linkAbi().get();
	}


	abstract /*final*/ class LinkAbiExtension {
		@Inject
		public LinkAbiExtension(FileCollection libs) {
			getFis().from(getLinkLibInputs().map(it -> {
				return it.values().stream().flatMap(t -> {
					if (t instanceof LibraryFileAware) {
						return Stream.of(((LibraryFileAware) t).getFile());
					}
					return Stream.empty();
				}).collect(Collectors.toList());
			}));
			getLinkLibInputs().set(libs.getElements().map(libsx -> {
				Path root = getLayout().getProjectDirectory().getAsFile().toPath();
				AbiExtractorService extractor = getAbiExtractor();
				Map<String, AbiModel> result = new LinkedHashMap<>();
				for (FileSystemLocation lib : libsx) {
					Map.Entry<String, AbiModel> entry = extractor.extract(lib.getAsFile(), root);
					result.put(entry.getKey(), entry.getValue());
				}
				return result;
			}));
		}

		@Inject
		protected abstract ProjectLayout getLayout();

		@Nested
		protected abstract AbiExtractorService getAbiExtractor();

		@Internal
		protected abstract MapProperty<String, AbiModel> getLinkLibInputs();

		@Input
		protected Provider<Map<String, AbiModel>> getIns() {
			return getLinkLibInputs().map(it -> {
				return it.entrySet().stream().filter(t -> !(t.getValue() instanceof LibraryFileAware)).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			});
		}

		@InputFiles
		protected abstract ConfigurableFileCollection getFis();
	}
}
