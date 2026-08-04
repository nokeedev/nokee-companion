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
import org.gradle.api.tasks.*;
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
			private final Collection<AbiBinaryHasher.HasExportSymbols> values;
			private final List<Path> filesToSnapshot;
			private final Set<Object> unresolvedImports;

			private Result(Collection<AbiBinaryHasher.HasExportSymbols> values, List<Path> filesToSnapshot, Set<Object> unresolvedImports) {
				this.values = values;
				this.filesToSnapshot = filesToSnapshot;
				this.unresolvedImports = unresolvedImports;
			}

			public Set<Object> unresolvedImports() {
				return unresolvedImports;
			}
		}

		private static final class ImportAndLib {
			private final List<Path> filesToSnapshot;
			private final Set<Object> allImports;
			private final Set<AbiBinaryHasher.HasExportSymbols> result;
			private final AbiSnapshotter snapshotter;

			public ImportAndLib(List<Path> filesToSnapshot, Set<Object> allImports, Set<AbiBinaryHasher.HasExportSymbols> result, AbiSnapshotter snapshotter) {
				this.filesToSnapshot = filesToSnapshot;
				this.allImports = allImports;
				this.result = result;
				this.snapshotter = snapshotter;
			}
		}

		@Inject
		public LinkAbiExtension(ObjectFactory objects) {
			libraryAbiModelsProps = objects.setProperty(new TypeOf<Map<String, Object>>() {}.getConcreteClass());
			libraryAbiModels = objects.listProperty(AbiBinaryHasher.AbiBinaryHashCode.class);
			linkLibInputs = objects.setProperty(Object.class);

			final Provider<Boolean> useAbi = getUseNormalizedAbi().orElse(false);

			SetProperty<Object> objImports = objects.setProperty(Object.class);
			objImports.set(getSource().getElements().map(elements -> {
				if (!useAbi.get()) return null;

				Set<Object> result = new LinkedHashSet<>();
				for (FileSystemLocation file : elements) {
					try {
						getAbiExtractor().visitImports(file.getAsFile().toPath(), result::add);
					} catch (Throwable t) {
						System.out.println("Could not parse object file '" + file.getAsFile() + "'");
						return null; // stop early without imports
					}
				}
				return result;
			}));
			objImports.disallowChanges();
			objImports.finalizeValueOnRead();

			Property<ImportAndLib> abiBin = objects.property(ImportAndLib.class);
			abiBin.set(objImports.zip(getLibs().getElements(), (allImports, elements) -> {
				if (!useAbi.get()) return null;

				class Visitor implements StaticOrSharedVisitor {
					AbiSnapshotter snapshotter = AbiSnapshotter.NARROW_ABI;
					List<Path> filesToSnapshot = new ArrayList<>();
					Set<AbiBinaryHasher.HasExportSymbols> result = new LinkedHashSet<>();

					@Override
					public void visitImports(Object symbol) {
						if (snapshotter == AbiSnapshotter.NARROW_ABI) {
							allImports.add(symbol);
						}
					}

					@Override
					public void visitStaticLib(Path path) {
						filesToSnapshot.add(path);
					}

					@Override
					public void visitBrokenStaticLib(Path path) {
						snapshotter = AbiSnapshotter.FULL_ABI;
						allImports.clear();
					}

					@Override
					public void visitUnknownLib(Path path) {
						filesToSnapshot.add(path);
					}

					@Override
					public void visitSharedLib(AbiBinaryHasher.HasExportSymbols hashcode) {
						result.add(hashcode);
					}
				}
				Visitor visitor = new Visitor();
				for (FileSystemLocation file : elements) {
					getAbiExtractor().visit(file.getAsFile().toPath(), visitor);
				}
				return new ImportAndLib(visitor.filesToSnapshot, allImports, visitor.result, visitor.snapshotter);
			}));
			abiBin.disallowChanges();
			abiBin.finalizeValueOnRead();

			Property<Result> snap = objects.property(Result.class);
			snap.set(abiBin.map(codes -> {
				// found all imports, narrowing the ABI
				if (codes.snapshotter == AbiSnapshotter.NARROW_ABI) {
					Set<Object> unresolvedImports = new LinkedHashSet<>(codes.allImports);
					List<AbiBinaryHasher.HasExportSymbols> result = codes.result.stream().map(it -> {
						return it.narrowExports(codes.allImports, unresolvedImports);
					}).collect(Collectors.toList());
					System.out.println("STATS " + codes.allImports.size() + " -- " + unresolvedImports.size() + " -- " + codes.snapshotter);
					return new Result(result, codes.filesToSnapshot, unresolvedImports);
				}
				return new Result(codes.result, codes.filesToSnapshot, Collections.emptySet());
			}));
			snap.disallowChanges();
			snap.finalizeValueOnRead();

			getUnresolvedImports().set(snap.map(it -> it.unresolvedImports()));


			getLibraryFiles().from(snap.map(it -> {
				return it.filesToSnapshot;
			}));
			getLibraryAbiModels().set(snap.map(it -> {
				return (Collection) it.values;
			}));
			getLibraryAbiModels().disallowChanges();
			getLibraryAbiModels().finalizeValueOnRead();
//			getLinkLibInputs().set(snap.map(it -> it.values));
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
