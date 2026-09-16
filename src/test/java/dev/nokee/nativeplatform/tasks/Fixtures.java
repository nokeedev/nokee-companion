package dev.nokee.nativeplatform.tasks;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The prebuilt binaries the readers are tested against, which live in src/test/resources/fixtures.
 * See that directory's BUILD file for what each one covers and how to rebuild it.
 */
final class Fixtures {
	private Fixtures() {}

	static Path fixture(String relativePath) {
		URL resource = Fixtures.class.getResource("/fixtures/" + relativePath);
		if (resource == null) {
			throw new AssertionError("Fixture not found: " + relativePath
				+ " — rebuild it with src/test/resources/fixtures/build-fixtures.sh");
		}

		try {
			return Paths.get(resource.toURI());
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
}
