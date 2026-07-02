package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
			ObjectFactory objects = getProject().getObjects();
			LinkAbiExtension extension = objects.newInstance(LinkAbiExtension.class, libs);
			extension.getExtractor().set(getProject().getGradle().getSharedServices().registerIfAbsent("link-abi-cache", LinkAbiCache.class).map(it -> objects.newInstance(CachingNativeLibraryAbiExtractor.class, it)));
			getExt_linkAbi().set(extension);
		}

		return getExt_linkAbi().get();
	}


	abstract /*final*/ class LinkAbiExtension {
		@Internal
		protected abstract Property<CachingNativeLibraryAbiExtractor> getExtractor();

		@Inject
		public LinkAbiExtension(FileCollection libs) {
			getFis().from(getLinkLibInputs().map(it -> {
				return it.stream().flatMap(t -> {
					if (t instanceof File) {
						return Stream.of((File) t);
					}
					return Stream.empty();
				}).collect(Collectors.toList());
			}));
			getIns().set(getLinkLibInputs().map(it -> {
				return it.stream().flatMap(t -> {
					if (t instanceof Map.Entry) {
						return Stream.of((Map.Entry<String, AbiModel>) t);
					}
					return Stream.empty();
				}).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			}));
			getIns().disallowChanges();
			getIns().finalizeValueOnRead();
			getLinkLibInputs().set(libs.getElements().map(libsx -> {
				Path root = getLayout().getProjectDirectory().getAsFile().toPath();
				AbiExtractorService extractor = getAbiExtractor();
				List<Object> result = new ArrayList<>();
				for (FileSystemLocation lib : libsx) {
					Object entry = extractor.extract(lib.getAsFile(), root);
					result.add(entry);
				}
				return result;
			}));
			getLinkLibInputs().finalizeValueOnRead(); // ensure one resolution per snapshot
			getLinkLibInputs().disallowChanges();
		}

		@Inject protected abstract ProjectLayout getLayout();
		@Inject protected abstract ObjectFactory getObjects();

		private AbiExtractorService getAbiExtractor() {
			return getObjects().newInstance(AbiExtractorService.class, getExtractor().get());
		}

		@Internal
		protected abstract SetProperty<Object> getLinkLibInputs();

		@Input
		protected abstract MapProperty<String, AbiModel> getIns();

		@InputFiles
		protected abstract ConfigurableFileCollection getFis();
	}
}
