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
// Segredos FICTICIOS, declarados em codigo de teste e nao no application.yml,
// para que nenhum valor de chave more no yml. Ver SegredosDeTeste.
@SpringBootTest(properties = { SegredosDeTeste.ENCRYPTION_KEY,
		SegredosDeTeste.JWT_SECRET, SegredosDeTeste.JWT_REFRESH_SECRET })
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ApplicationContextTest {

	@Test
	void contextoSobeEMigrationsAplicam() {
		// O proprio carregamento do contexto e a asercao.
	}

}
