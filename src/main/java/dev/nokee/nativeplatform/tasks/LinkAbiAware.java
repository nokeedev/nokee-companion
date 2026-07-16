package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// This class is considered private for the moment
public interface LinkAbiAware extends Task {
	@Internal
	Property<LinkAbiExtension> getExt_linkAbi();

	@Nested
	default LinkAbiExtension getLinkAbi() {
		if (!getExt_linkAbi().isPresent()) { // safe as we control the lifecycle
			ObjectFactory objects = getProject().getObjects();
			LinkAbiExtension extension = objects.newInstance(LinkAbiExtension.class);
			extension.getLinkAbiCache().set(getProject().getGradle().getSharedServices().registerIfAbsent("link-abi-cache", LinkAbiCache.class));
			getExt_linkAbi().set(extension);
		}

		return getExt_linkAbi().get();
	}


	abstract /*final*/ class LinkAbiExtension {
		@Internal
		protected abstract Property<LinkAbiCache> getLinkAbiCache();

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
			final Provider<Boolean> useAbi = getUseNormalizedAbi().orElse(false);
			getLinkLibInputs().set(getLibs().getElements().map(libs -> {
				if (useAbi.get()) {
					NativeLibraryAbiExtractor extractor = getAbiExtractor();
					List<Object> result = new ArrayList<>();
					for (FileSystemLocation lib : libs) {
						Object entry = extractor.hash(lib.getAsFile().toPath());
						result.add(entry);
					}
					return result;
				}
				return libs;
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

		@Input
		@Optional
		public abstract Property<Boolean> getUseNormalizedAbi();

		@Inject protected abstract ObjectFactory getObjects();

		private NativeLibraryAbiExtractor getAbiExtractor() {
			return getObjects().newInstance(CachingNativeLibraryAbiExtractor.class, getLinkAbiCache().get());
		}

		@Internal
		protected SetProperty<Object> getLinkLibInputs() {
			return linkLibInputs;
		}

		@Internal
		protected ListProperty<AbiBinaryHasher.AbiBinaryHashCode> getLibraryAbiModels() {
			return libraryAbiModels;
		}

		@Input // This pattern is @Nested while respecting the provider knowledge
		// Note that this pattern must split the "@InputFiles"/"@OutputFiles" from the "@Input" values as we don't have real @Nested
		protected SetProperty<Map<String, Object>> getLibraryAbiModelsProps() {
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
