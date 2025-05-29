package dev.nokee.companion;

import org.gradle.api.Incubating;
import org.gradle.api.Named;
import org.gradle.api.attributes.Attribute;

@Incubating
public interface TestIntegrationType extends Named {
	Attribute<TestIntegrationType> TEST_INTEGRATION_TYPE_ATTRIBUTE = Attribute.of("dev.nokee.testing.integration-type", TestIntegrationType.class);
	String SOURCE_LEVEL = "source-level";
	String LINK_LEVEL = "link-level";
	String PRODUCT_LEVEL = "product-level";
}
