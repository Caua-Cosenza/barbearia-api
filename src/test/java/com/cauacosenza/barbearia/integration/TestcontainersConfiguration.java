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
 *
 * <h2>Reuso</h2>
 *
 * <p>{@code withReuse(true)} existe para o mutation testing. Cada minion do
 * PIT e uma JVM nova, que sobe contexto do Spring e, sem reuso, o seu proprio
 * par Postgres+Redis; com 16 threads sao 16 pares simultaneos, e o run
 * completo entra em thrashing — timeout sobre timeout e containers orfaos.
 *
 * <p><b>O reuso nao se habilita aqui.</b> O {@code withReuse(true)} e so
 * metade: o Testcontainers exige, de proposito, que a outra metade venha da
 * maquina — {@code testcontainers.reuse.enable=true} em
 * {@code ~/.testcontainers.properties}, ou a variavel de ambiente
 * {@code TESTCONTAINERS_REUSE_ENABLE}. Nao ha como ligar pelo classpath do
 * projeto, e isso e bom: no CI o reuso continua desligado e cada build parte
 * de um banco novo, sem que ninguem precise se lembrar disso. Sem a metade da
 * maquina, esta chamada apenas emite um aviso e os containers sao descartados
 * como sempre.
 *
 * <p><b>O preco:</b> container reusado nao e recolhido pelo Ryuk no fim da
 * JVM — e esse o ponto. Ele sobrevive entre execucoes, e com ele sobrevive o
 * <b>estado</b>: as tabelas voltam limpas porque
 * {@code AbstractPostgresIntegrationTest} as trunca, mas o Redis nao e limpo
 * por ninguem, e as chaves de rate limit tem uma hora de TTL.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
				.withReuse(true);
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
				.withExposedPorts(6379)
				.withReuse(true);
	}

}
