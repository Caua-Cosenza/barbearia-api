package com.cauacosenza.barbearia.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Sobe o contexto inteiro contra Postgres e Redis reais e aplica as migrations
 * do Flyway.
 *
 * <p>Teste barato e de alto retorno: pega erro de configuracao, bean mal
 * declarado e migration invalida antes de qualquer endpoint existir.
 */
@SpringBootTest(properties = {
		// Chave FICTICIA, so para o contexto subir. Declarada aqui, e nao no
		// application.yml, para que nenhum valor de chave more no yml.
		"ENCRYPTION_KEY=4f8a2c1e9b7d3a5f6e0c8b4d2a9f1e7c3b5d8a0f2e6c4b9d1a7f3e5c8b2d6a0f"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ApplicationContextTest {

	@Test
	void contextoSobeEMigrationsAplicam() {
		// O proprio carregamento do contexto e a asercao.
	}

}
