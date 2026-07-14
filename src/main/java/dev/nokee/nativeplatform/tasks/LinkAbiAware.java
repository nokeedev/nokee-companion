package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

interface LinkAbiAware extends Task {
	@Internal
	Property<LinkAbiExtension> getExt_linkAbi();

	@Nested
	default LinkAbiExtension getLinkAbi() {
		if (!getExt_linkAbi().isPresent()) { // safe as we control the lifecycle
			ObjectFactory objects = getProject().getObjects();
			LinkAbiExtension extension = objects.newInstance(LinkAbiExtension.class);
			extension.getExtractor().set(getProject().getGradle().getSharedServices().registerIfAbsent("link-abi-cache", LinkAbiCache.class).map(it -> objects.newInstance(CachingNativeLibraryAbiExtractor.class, it)));
			getExt_linkAbi().set(extension);
		}

		return getExt_linkAbi().get();
	}


	abstract /*final*/ class LinkAbiExtension {
		@Internal
		protected abstract Property<CachingNativeLibraryAbiExtractor> getExtractor();

		private SetProperty<Map<String, Object>> libraryAbiModelsProps;
		private ListProperty<AbiBinaryHasher.AbiBinaryHashCode> libraryAbiModels;
		private SetProperty<Object> linkLibInputs;

		@Inject
		public LinkAbiExtension(ObjectFactory objects) {
			libraryAbiModelsProps = objects.setProperty(new TypeOf<Map<String, Object>>() {}.getConcreteClass());
			libraryAbiModels = objects.listProperty(AbiBinaryHasher.AbiBinaryHashCode.class);
			linkLibInputs = objects.setProperty(Object.class);

			getLibraryFiles().from(getLinkLibInputs().map(it -> {
				return it.stream().flatMap(t -> {
					if (t instanceof File) {
						return Stream.of((File) t);
					}
					return Stream.empty();
				}).collect(Collectors.toList());
			}));
			getLibraryAbiModels().set(getLinkLibInputs().map(it -> {
				return it.stream().flatMap(t -> {
					if (t instanceof AbiBinaryHasher.AbiBinaryHashCode) {
						return Stream.of((AbiBinaryHasher.AbiBinaryHashCode) t);
					}
					return Stream.empty();
				}).collect(Collectors.toList());
			}));
			getLibraryAbiModels().disallowChanges();
			getLibraryAbiModels().finalizeValueOnRead();
			getLinkLibInputs().set(getLibs().getElements().map(libsx -> {
				AbiExtractorService extractor = getAbiExtractor();
				List<Object> result = new ArrayList<>();
				for (FileSystemLocation lib : libsx) {
					Object entry = extractor.hash(lib.getAsFile());
					result.add(entry);
				}
				return result;
			}));
			getLinkLibInputs().finalizeValueOnRead(); // ensure one resolution per snapshot
			getLinkLibInputs().disallowChanges();

			getLibraryAbiModelsProps().set(getLibraryAbiModels().map(values -> {
				final Set<Map<String, Object>> result = new LinkedHashSet<>();
				for (AbiBinaryHasher.AbiBinaryHashCode value : values) {
					if (value instanceof Map) {
						@SuppressWarnings("unchecked")
						final Map<String, Object> v = (Map<String, Object>) value;
						result.add(v);
					} else {
						throw new RuntimeException();
					}
				}
				return result;
			}));
			getLibraryAbiModelsProps().finalizeValueOnRead();
			getLibraryAbiModelsProps().disallowChanges();
		}

		@Internal
		public abstract ConfigurableFileCollection getLibs();

		@Inject protected abstract ObjectFactory getObjects();

		private AbiExtractorService getAbiExtractor() {
			return getObjects().newInstance(AbiExtractorService.class, getExtractor().get());
		}

		@Internal
		protected SetProperty<Object> getLinkLibInputs() {
			return linkLibInputs;
		}

		@Internal
		protected ListProperty<AbiBinaryHasher.AbiBinaryHashCode> getLibraryAbiModels() {
			return libraryAbiModels;
		}

		@Input
		SetProperty<Map<String, Object>> getLibraryAbiModelsProps() {
			return libraryAbiModelsProps;
		}

		@InputFiles
		protected abstract ConfigurableFileCollection getLibraryFiles();

		void close() {
			linkLibInputs = null;
			libraryAbiModels = null;
			libraryAbiModelsProps = null;
		}
	}
}
