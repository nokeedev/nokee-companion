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
import java.nio.file.Path;
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

		private enum AbiSnapshotter {
			FULL_ABI,
			NARROW_ABI
		}

		private static final class Result {
			private final List<Object> values;
			private final Set<Object> unresolvedImports;

			private Result(List<Object> values, Set<Object> unresolvedImports) {
				this.values = values;
				this.unresolvedImports = unresolvedImports;
			}

			public List<Object> get() {
				return values;
			}

			public Set<Object> unresolvedImports() {
				return unresolvedImports;
			}
		}

		@Inject
		public LinkAbiExtension(ObjectFactory objects) {
			libraryAbiModelsProps = objects.setProperty(new TypeOf<Map<String, Object>>() {}.getConcreteClass());
			libraryAbiModels = objects.listProperty(AbiBinaryHasher.AbiBinaryHashCode.class);
			linkLibInputs = objects.setProperty(Object.class);

			final Provider<Boolean> useAbi = getUseNormalizedAbi().orElse(false);
			SetProperty<Object> abiBin = objects.setProperty(Object.class);
			abiBin.addAll(getSource().getElements().map(elements -> {
				if (!useAbi.get()) return Collections.emptyList();

				Set<AbiBinaryHasher.AbiBinaryHashCode> result = new LinkedHashSet<>();
				for (FileSystemLocation file : elements) {
					AbiBinaryHasher.AbiBinaryHashCode hashcode = getAbiExtractor().hashObject(file.getAsFile().toPath());
					result.add(hashcode);
					if (hashcode instanceof AbiBinaryHasher.Unknown) {
						System.out.println("Could not parse object file '" + file.getAsFile() + "'");
						return result; // stop early
					}
				}
				return result;
			}));
			abiBin.addAll(getLibs().getElements().map(elements -> {
				if (!useAbi.get()) return elements;

				Set<AbiBinaryHasher.AbiBinaryHashCode> result = new LinkedHashSet<>();
				for (FileSystemLocation file : elements) {
					result.add(getAbiExtractor().hash(file.getAsFile().toPath()));
				}
				return result;
			}));
			abiBin.disallowChanges();
			abiBin.finalizeValueOnRead();

			Property<Result> snap = objects.property(Result.class);
			snap.set(abiBin.map(codes -> {
				AbiSnapshotter snapshotter = AbiSnapshotter.NARROW_ABI;
				List<Object> result = new ArrayList<>();
				Set<Object> allImports = new HashSet<>();
				for (Object c : codes) {
					if (!(c instanceof AbiBinaryHasher.AbiBinaryHashCode)) {
						result.add(c);
						continue;
					}
					AbiBinaryHasher.AbiBinaryHashCode code = (AbiBinaryHasher.AbiBinaryHashCode) c;

					// A file we could not classify might be an import source with unknown imports, so we can
					// never narrow. Byte-snapshot it (when it is a library input) to stay correct.
					if (code instanceof AbiBinaryHasher.Unknown) {
						System.out.println("Snapshotting full ABI instead due to unknown object");
						snapshotter = AbiSnapshotter.FULL_ABI;
						if (code instanceof AbiBinaryHasher.HasLocation) {
							result.add(((AbiBinaryHasher.HasLocation) code).location());
						}
						continue;
					}

					// Import sources (object files, static-lib members) donate the names narrowing keeps.
					// If any importer's imports cannot be determined, the import set is incomplete and we
					// must not narrow.
					if (snapshotter == AbiSnapshotter.NARROW_ABI && (code.type() == AbiBinaryHasher.Type.OBJECT_FILE || code.type() == AbiBinaryHasher.Type.STATIC_LIB)) {
						if (!collectImports(code, allImports)) {
							System.out.println("Snapshotting full ABI due to unable to collect imports");
							snapshotter = AbiSnapshotter.FULL_ABI;
						}
					}
					if (code.type() == AbiBinaryHasher.Type.STATIC_LIB) {
						result.add(((AbiBinaryHasher.HasLocation) code).location());
					} else if (code.type() == AbiBinaryHasher.Type.DYNAMIC_LIB) {
						if (code instanceof AbiBinaryHasher.HasExportSymbols) {
							result.add(code);
						} else {
							result.add(((AbiBinaryHasher.HasLocation) code).location());
						}
					}
					// An OBJECT_FILE contributes imports only; it is tracked as task source, not a snapshot input.
				}

				// found all imports, narrowing the ABI
				Set<Object> unresolvedImports = new LinkedHashSet<>(allImports);
				if (snapshotter == AbiSnapshotter.NARROW_ABI) {
					result = result.stream().map(it -> {
						if (it instanceof AbiBinaryHasher.HasExportSymbols) {
							return ((AbiBinaryHasher.HasExportSymbols) it).narrowExports(allImports, unresolvedImports);
						}
						return it;
					}).collect(Collectors.toList());
				}
				return new Result(result, unresolvedImports);
			}));
			snap.disallowChanges();
			snap.finalizeValueOnRead();

			getUnresolvedImports().set(snap.map(it -> it.unresolvedImports()));


			getLibraryFiles().from(getLinkLibInputs().map(it -> {
				return it.stream().flatMap(t -> {
					if (t instanceof File || t instanceof FileSystemLocation || t instanceof Path) {
						return Stream.of(t);
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
			getLinkLibInputs().set(snap.map(it -> it.get()));
//			getLinkLibInputs().set(getLibs().getElements().map(libs -> {
//				if (useAbi.get()) {
//					NativeLibraryAbiExtractor extractor = getAbiExtractor();
//					List<Object> result = new ArrayList<>();
//					for (FileSystemLocation lib : libs) {
//						Object entry = extractor.hash(lib.getAsFile().toPath());
//						result.add(entry);
//					}
//					return result;
//				}
//				return libs;
//			}));
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

		// Collects the imported names of an import source into {@code into}, recursing into an archive's
		// members. Returns {@code false} when a code exposes neither imports nor members — i.e. its imports
		// cannot be determined, so the link must fall back to the full ABI.
		private static boolean collectImports(AbiBinaryHasher.AbiBinaryHashCode code, Set<Object> into) {
			if (code instanceof AbiBinaryHasher.HasImportSymbols) {
				into.addAll(((AbiBinaryHasher.HasImportSymbols) code).getImportedSymbols());
				return true;
			}
			if (code instanceof AbiBinaryHasher.HasMembers) {
				boolean complete = true;
				for (AbiBinaryHasher.AbiBinaryHashCode member : ((AbiBinaryHasher.HasMembers) code).getMembers()) {
					complete &= collectImports(member, into);
				}
				return complete;
			}
			return false;
		}

		@Internal
		public abstract ConfigurableFileCollection getSource();

		@Internal
		public abstract ConfigurableFileCollection getLibs();

		@Input
		@Optional
		public abstract Property<Boolean> getUseNormalizedAbi();

		@Input
		protected abstract SetProperty<Object> getUnresolvedImports();

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
