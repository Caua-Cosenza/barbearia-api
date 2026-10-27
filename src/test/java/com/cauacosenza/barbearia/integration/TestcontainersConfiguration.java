package com.cauacosenza.barbearia.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres e Redis reais para os testes de integracao.
 *
 * <p>{@code @ServiceConnection} registra os dados de conexao direto no
 * contexto, por isso o perfil {@code test} nao define
 * {@code spring.datasource.*} nem {@code spring.data.redis.*}: quem manda e o
 * container.
 *
 * <p>As imagens sao fixadas nas mesmas versoes do docker-compose. Testar
 * contra {@code postgres:latest} e dar o teste por bom em um banco que nao e o
 * de producao.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

}
