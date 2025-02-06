package dev.nokee.companion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradleWrapper {
	public static Properties wrapperProperties(Path path) {
		java.util.Properties properties = new java.util.Properties();
		try (InputStream inStream = Files.newInputStream(path)) {
			properties.load(inStream);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return new Properties() {
			@Override
			public String getGradleVersion() {
				String distributionUrl = properties.getProperty("distributionUrl");
				Matcher m = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?").matcher(distributionUrl);
				if (m.find()) {
					return m.group();
				}
				return null;
			}
		};
	}

	public interface Properties {
		String getGradleVersion();
	}
}
