package com.cauacosenza.barbearia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * A verificacao de schema no boot, nos dois sentidos.
 *
 * <p><b>Por que com mock e nao derrubando a constraint de verdade.</b> O
 * caminho obvio seria um teste de integracao que da
 * {@code DROP CONSTRAINT} e tenta subir o contexto. Nao da: o container de
 * Postgres e <b>reusado</b> entre execucoes e compartilhado por toda a suite,
 * entao uma queda de constraint que nao seja desfeita deixa o banco sem a
 * garantia — e o proximo teste de concorrencia passaria verde medindo nada.
 * Um teste que precisa de {@code try/finally} para nao estragar o ambiente de
 * todos os outros e um teste que, ao falhar no meio, estraga.
 *
 * <p>Os dois ramos de decisao sao logica pura sobre o resultado da consulta,
 * e o mock os cobre exatamente. O que o mock <b>nao</b> prova e que a
 * consulta ao catalogo funciona e que o nome da constraint esta certo — isso
 * fica em {@link com.cauacosenza.barbearia.integration.ConstraintDeSobreposicaoNoBancoTest},
 * contra o banco real.
 */
class VerificaAConstraintDeSobreposicaoTest {

	/** O que o catalogo devolve quando a V2 esta aplicada. */
	private static final List<String> COM_A_CONSTRAINT =
			List.of(VerificaAConstraintDeSobreposicao.CONSTRAINT);

	@Nested
	@DisplayName("com a constraint presente")
	class Presente {

		@Test
		@DisplayName("sobe em prod")
		void sobeEmProd() {
			assertThatCode(() -> verificarCom("prod", COM_A_CONSTRAINT))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("sobe em dev")
		void sobeEmDev() {
			assertThatCode(() -> verificarCom("dev", COM_A_CONSTRAINT))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("outra constraint de exclusao nao serve no lugar dela")
		void outraExclusaoNaoServe() {
			// A consulta devolve TODAS as exclusoes da tabela. Aceitar
			// "existe alguma" em vez de "existe esta" deixaria passar um
			// banco onde a constraint foi recriada com regra diferente.
			assertThatIllegalStateException()
					.isThrownBy(() -> verificarCom("prod", List.of("appointments_outra_regra")));
		}

	}

	@Nested
	@DisplayName("com a coluna completed_at ausente")
	class ColunaAusente {

		@Test
		@DisplayName("prod NAO sobe, mesmo com a constraint presente")
		void prodNaoSobe() {
			// As duas perdas vem do mesmo `db push`, mas podem aparecer
			// separadas: alguem pode reaplicar a V2 na mao e esquecer a V3.
			assertThatIllegalStateException()
					.isThrownBy(() -> verificarCom("prod", COM_A_CONSTRAINT, false))
					.withMessageContaining(VerificaAConstraintDeSobreposicao.COLUNA)
					.withMessageContaining("fim REAL")
					.withMessageContaining("prisma db push");
		}

		@Test
		@DisplayName("a mensagem diz o que se perde, nao so que a coluna falta")
		void mensagemDizOQueSePerde() {
			assertThatIllegalStateException()
					.isThrownBy(() -> verificarCom("prod", COM_A_CONSTRAINT, false))
					.satisfies(erro -> assertThat(erro.getMessage())
							.contains("50 minutos")
							.contains("devolver"));
		}

		@Test
		@DisplayName("dev sobe com WARN")
		void devSobe() {
			assertThatCode(() -> verificarCom("dev", COM_A_CONSTRAINT, false))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("faltando as DUAS, a mensagem fala das duas")
		void faltandoAsDuas() {
			assertThatIllegalStateException()
					.isThrownBy(() -> verificarCom("prod", List.of(), false))
					.satisfies(erro -> assertThat(erro.getMessage())
							.contains(VerificaAConstraintDeSobreposicao.CONSTRAINT)
							.contains(VerificaAConstraintDeSobreposicao.COLUNA)
							// a causa aparece UMA vez, nao duas
							.satisfies(m -> assertThat(m.split("CAUSA PROVAVEL")).hasSize(2)));
		}

	}

	@Nested
	@DisplayName("com a constraint ausente")
	class Ausente {

		@Test
		@DisplayName("prod NAO sobe, e a mensagem nomeia a constraint e a causa")
		void prodNaoSobe() {
			assertThatIllegalStateException()
					.isThrownBy(() -> verificarCom("prod", List.of()))
					.withMessageContaining(VerificaAConstraintDeSobreposicao.CONSTRAINT)
					.withMessageContaining("prisma db push")
					.withMessageContaining("nenhuma");
		}

		@Test
		@DisplayName("dev sobe, porque e nele que se investiga o problema")
		void devSobe() {
			// Exigir a constraint fora de producao impediria de levantar a
			// aplicacao contra um banco antigo justamente para corrigi-lo.
			assertThatCode(() -> verificarCom("dev", List.of()))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("test tambem sobe")
		void testSobe() {
			assertThatCode(() -> verificarCom("test", List.of()))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("a mensagem diz a consequencia, nao so o sintoma")
		void mensagemDizAConsequencia() {
			// Quem le o log as 22h de uma sexta precisa saber o que esta
			// desprotegido, nao caçar o significado de "constraint ausente".
			assertThatIllegalStateException()
					.isThrownBy(() -> verificarCom("prod", List.of()))
					.satisfies(erro -> assertThat(erro.getMessage())
							.contains("201")
							.contains("duas linhas")
							.contains("V2"));
		}

	}

	// =======================================================================

	private static void verificarCom(String perfil, List<String> exclusoesNoBanco) {
		verificarCom(perfil, exclusoesNoBanco, true);
	}

	/**
	 * <p>As duas consultas sao distinguidas pelo SQL, e nao por ordem de
	 * chamada: um mock que devolvesse a mesma lista para as duas faria a
	 * presenca da coluna seguir a presenca da constraint, e nenhum dos casos de
	 * ausencia isolada seria exercitado.
	 */
	private static void verificarCom(String perfil, List<String> exclusoesNoBanco, boolean temColuna) {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		given(jdbc.queryForList(contains("pg_constraint"), eq(String.class)))
				.willReturn(exclusoesNoBanco);
		given(jdbc.queryForList(contains("information_schema"), eq(String.class)))
				.willReturn(temColuna ? List.of(VerificaAConstraintDeSobreposicao.COLUNA) : List.of());

		MockEnvironment ambiente = new MockEnvironment();
		ambiente.setActiveProfiles(perfil);

		new VerificaAConstraintDeSobreposicao(jdbc, ambiente).verificar();
	}

}
