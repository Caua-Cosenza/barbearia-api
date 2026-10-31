package com.cauacosenza.barbearia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * O {@code EDGE_TOKEN} na validacao de ambiente.
 *
 * <p><b>Por que este teste existe.</b> O {@link EnvironmentValidator} e
 * {@code @Profile("!test")}, entao nenhum teste de contexto o executa — a
 * classe estava com <b>zero</b> de cobertura quando o {@code EDGE_TOKEN}
 * entrou nela. E codigo que so roda em producao, no boot, e cujo defeito
 * aparece como "a aplicacao subiu quando nao devia" ou "a aplicacao nao sobe e
 * a mensagem nao diz por que". Chamar {@code validate()} direto, com um
 * {@link MockEnvironment}, cobre os dois sentidos sem subir contexto.
 *
 * <p>Nao e um teste do validador inteiro: so da variavel que foi acrescentada.
 */
class EdgeTokenNoAmbienteTest {

	private static final String TOKEN_VALIDO = "um-segredo-de-borda-com-mais-de-32-caracteres";

	@Test
	@DisplayName("prod sem EDGE_TOKEN: nao sobe, e a mensagem diz qual variavel falta")
	void prodExigeOEdgeToken() {
		assertThatIllegalStateException()
				.isThrownBy(() -> validarCom(ambiente("prod", null)))
				.withMessageContaining("EDGE_TOKEN")
				.withMessageContaining("obrigatoria");
	}

	@Test
	@DisplayName("prod com EDGE_TOKEN curto: nao sobe, e a mensagem diz o minimo")
	void prodExigeTamanhoMinimo() {
		assertThatIllegalStateException()
				.isThrownBy(() -> validarCom(ambiente("prod", "curto")))
				.withMessageContaining("EDGE_TOKEN")
				.withMessageContaining("32");
	}

	@Test
	@DisplayName("prod com EDGE_TOKEN valido: sobe")
	void prodAceitaUmTokenValido() {
		assertThatCode(() -> validarCom(ambiente("prod", TOKEN_VALIDO)))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dev sem EDGE_TOKEN: sobe — nao ha borda no caminho")
	void devNaoExigeOEdgeToken() {
		// Em desenvolvimento a aplicacao e alcancada direto, o fallback para
		// getRemoteAddr() e o comportamento certo, e exigir o segredo so
		// atrapalharia quem clona o projeto.
		assertThatCode(() -> validarCom(ambiente("dev", null)))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("o valor do segredo nunca aparece na mensagem de erro")
	void aMensagemNaoVazaOSegredo() {
		// Mesma regra dos outros segredos: logar um valor invalido e vaza-lo.
		// "curto" seria inofensivo; um segredo real digitado com um caractere
		// a menos nao seria.
		String quaseCerto = "segredo-de-borda-quase-correto!";

		// O pressuposto do teste, afirmado: se este valor passar a ser valido,
		// nenhuma excecao e lancada e o teste nao prova nada sobre a mensagem.
		// Foi exatamente o que aconteceu na primeira versao, com uma string
		// que eu contei errado.
		assertThat(quaseCerto).hasSizeLessThan(32);

		assertThatIllegalStateException()
				.isThrownBy(() -> validarCom(ambiente("prod", quaseCerto)))
				.satisfies(erro -> assertThat(erro.getMessage()).doesNotContain(quaseCerto));
	}

	// =======================================================================

	private static void validarCom(MockEnvironment ambiente) {
		EnvironmentValidator validador = new EnvironmentValidator();
		validador.setEnvironment(ambiente);
		validador.validate();
	}

	/**
	 * Um ambiente em que tudo o mais esta certo.
	 *
	 * <p>As demais obrigatorias precisam estar presentes porque o validador
	 * junta todas as falhas numa mensagem: faltando uma delas, as asercoes
	 * sobre {@code EDGE_TOKEN} passariam sem provar nada sobre ele.
	 */
	private static MockEnvironment ambiente(String perfil, String edgeToken) {
		MockEnvironment ambiente = new MockEnvironment();
		ambiente.setActiveProfiles(perfil);
		ambiente.setProperty("DATABASE_URL", "jdbc:postgresql://localhost:5432/barbearia");
		ambiente.setProperty("REDIS_URL", "redis://localhost:6379");
		ambiente.setProperty("JWT_SECRET", "um-segredo-de-jwt-com-mais-de-32-caracteres");
		ambiente.setProperty("JWT_REFRESH_SECRET", "outro-segredo-de-jwt-com-mais-de-32-caract");
		ambiente.setProperty("ENCRYPTION_KEY", "0".repeat(64));
		ambiente.setProperty("CORS_ORIGIN", "https://exemplo.com.br");
		if (edgeToken != null) {
			ambiente.setProperty("EDGE_TOKEN", edgeToken);
		}
		return ambiente;
	}

}
