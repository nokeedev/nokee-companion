package dev.nokee.companion;

import org.hamcrest.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.function.Supplier;

public class CompilationOutputs {
	private final TargetDirectory targetDirectory;

	private CompilationOutputs(TargetDirectory targetDirectory) {
		this.targetDirectory = targetDirectory;
	}

	public static CompilationOutputs from(Path targetDirectory) {
		return new CompilationOutputs(new TargetDirectory(targetDirectory));
	}

	public CompilationOutputs withExtensions(String... extensions) {
		return new CompilationOutputs(targetDirectory.withExtensions(Arrays.asList(extensions)));
	}

	private static final class TargetDirectory {
		private final Path targetDirectory;
		private final Iterable<String> includeExtensions;

		public TargetDirectory(Path targetDirectory) {
			this(targetDirectory, Collections.emptyList());
		}

		private TargetDirectory(Path targetDirectory, Iterable<String> includeExtensions) {
			this.targetDirectory = targetDirectory;
			this.includeExtensions = includeExtensions;
		}

		public TargetDirectory withExtensions(Iterable<String> extensions) {
			return new TargetDirectory(targetDirectory, extensions);
		}

		public void listFiles(FileVisitor<Path> visitor) {
			String pattern = String.join(",", includeExtensions);
			if (pattern.isEmpty()) {
				pattern = "*";
			} else {
				pattern = "*.{" + pattern + "}";
			}
			PathMatcher matcher = targetDirectory.getFileSystem().getPathMatcher("glob:**/" + pattern);
			try {
				Files.walkFileTree(targetDirectory, new FileVisitor<>() {
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
						return visitor.preVisitDirectory(dir, attrs);
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (matcher.matches(file)) {
							return visitor.visitFile(file, attrs);
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
						return visitor.visitFileFailed(file, exc);
					}

					@Override
					public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
						return visitor.postVisitDirectory(dir, exc);
					}
				});
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}


	// Makes a snapshot of outputs (sets the last modified timestamp to zero for all files)
	public Snapshot snapshot() {
		List<Path> snapshot = new ArrayList<>();

		targetDirectory.listFiles(new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.setLastModifiedTime(file, FileTime.fromMillis(0));
				snapshot.add(file);
				return FileVisitResult.CONTINUE;
			}
		});

		class Impl implements Snapshot, SnapshotInternal {
			@Override
			public Iterator<Path> iterator() {
				return snapshot.iterator();
			}

			@Override
			public TargetDirectory getTargetDirectory() {
				return targetDirectory;
			}
		}
		return new Impl();
	}

	public <R> Result<R> snapshot(Supplier<R> action) {
		final R result = action.get();
		SnapshotInternal snapshot = (SnapshotInternal) snapshot();
		class Impl implements Result<R>, SnapshotInternal {
			@Override
			public Iterator<Path> iterator() {
				return snapshot.iterator();
			}

			@Override
			public R get() {
				return result;
			}

			@Override
			public TargetDirectory getTargetDirectory() {
				return snapshot.getTargetDirectory();
			}
		}
		return new Impl();
	}

	public Snapshot snapshot(Runnable action) {
		action.run();
		return snapshot();
	}

	public interface Snapshot {

	}

	private interface SnapshotInternal extends Iterable<Path> {
		TargetDirectory getTargetDirectory();
	}

	public interface Result<R> extends Snapshot, Supplier<R> {

	}

	public static Matcher<Snapshot> noneRecompiled() {
		return recompiledFiles();
	}

	@SafeVarargs
	public static Matcher<Snapshot> recompiledFiles(Matcher<Object>... matchers) {
		return recompiledFiles(Arrays.asList(matchers));
	}

	public static Matcher<Snapshot> recompiledFiles(Collection<Matcher<Object>> matchers) {
		return new FeatureMatcher<Snapshot, Iterable<Path>>(Matchers.containsInAnyOrder(matchers), "", "") {
			@Override
			protected Iterable<Path> featureValueOf(Snapshot snapshot) {
				List<Path> changedFiles = new ArrayList<>();
				((SnapshotInternal) snapshot).getTargetDirectory().listFiles(new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (Files.getLastModifiedTime(file).toMillis() > 0) {
							changedFiles.add(file);
						}
						return FileVisitResult.CONTINUE;
					}
				});
				return changedFiles;
			}
		};
	}
}
