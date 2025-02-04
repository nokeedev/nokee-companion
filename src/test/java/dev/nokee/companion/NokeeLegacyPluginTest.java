package dev.nokee.companion;

import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.api.Project;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A simple unit test for the 'org.example.greeting' plugin.
 */
class NokeeLegacyPluginTest {
    @Test void pluginRegistersATask() {
        // Create a test project and apply the plugin
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("org.example.greeting");

        // Verify the result
        assertNotNull(project.getTasks().findByName("greeting"));
    }
}
