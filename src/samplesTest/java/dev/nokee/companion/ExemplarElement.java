package dev.nokee.companion;

import dev.gradleplugins.exemplarkit.MarkdownExemplarStepExtractor;
import dev.gradleplugins.exemplarkit.Step;
import dev.gradleplugins.exemplarkit.asciidoc.AsciidoctorExemplarStepExtractor;
import dev.gradleplugins.fixtures.sources.ProjectElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ExemplarElement<T extends ProjectElement> extends ProjectElement {
	private final T element;

	public ExemplarElement(T element) {
		this.element = element;
	}

	@Override
	public Path getLocation() {
		return element.getLocation();
	}

	@Override
	public ExemplarElement<T> writeToDirectory(Path directory) {
		return new ExemplarElement<T>((T) element.writeToDirectory(directory)) {
			@Override
			public String toString() {
				return "written " + ExemplarElement.this.element + " from '" + element.getLocation() + "'";
			}
		};
	}

	public List<Step> getSteps() {
		try {
			Path readme = element.getLocation().resolve("README.md");
			if (Files.exists(readme)) {
				return new MarkdownExemplarStepExtractor().extract(Files.readString(readme));
			}

			readme = element.getLocation().resolve("README.adoc");
			if (Files.exists(readme)) {
				return new AsciidoctorExemplarStepExtractor().extract(Files.readString(readme));
			}

			throw new UnsupportedOperationException();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String toString() {
		return "exemplar for " + element.toString();
	}
}
