package com.example.expense.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ApplicationSecurityPropertiesTest {

    @Test
    void exposesOnlyHealthActuatorEndpoint() throws Exception {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertThat(properties.getProperty("management.endpoints.enabled-by-default")).isEqualTo(false);
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(properties.getProperty("management.endpoint.health.enabled")).isEqualTo(true);
        assertThat(properties.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(properties.getProperty("management.endpoint.env.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("management.endpoint.heapdump.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("management.endpoint.loggers.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("management.endpoint.shutdown.enabled")).isEqualTo(false);
    }
}
