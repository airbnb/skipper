package com.example.orders;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * A throwaway MySQL for the test JVM. @ServiceConnection points spring.datasource at the container, so Flyway
 * applies Skipper's schema to it and Skipper's MySQL store runs against it, exactly as in production.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
  @Bean
  @ServiceConnection
  MySQLContainer mysql() {
    return new MySQLContainer("mysql:8.4");
  }
}
