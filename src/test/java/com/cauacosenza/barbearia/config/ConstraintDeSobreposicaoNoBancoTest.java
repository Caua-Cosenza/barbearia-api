package com.cauacosenza.barbearia.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.cauacosenza.barbearia.integration.AbstractPostgresIntegrationTest;

/**
 * A consulta ao catalogo funciona, e o nome da constraint esta certo.
 *
 * <p>Complemento de {@link VerificaAConstraintDeSobreposicaoTest}, que cobre
 * os dois ramos de decisao com mock. O que o mock nao consegue provar e
 * exatamente o que se erra na pratica: que o SQL contra {@code pg_constraint}
 * e valido, que {@code contype = 'x'} e de fato o codigo de exclusao, e que o
 * nome procurado e o mesmo que a V2 cria. Um erro de digitacao no nome
 * passaria por todos os testes de mock e so apareceria em producao, como uma
 * aplicacao que se recusa a subir contra um banco perfeitamente correto.
 *
 * <p>Fica no pacote {@code config}, e nao em {@code integration}, porque
 * {@code verificar()} e package-private — mesma razao pela qual o teste do
 * {@link EnvironmentValidator} tambem mora aqui.
 */
class ConstraintDeSobreposicaoNoBancoTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private VerificaAConstraintDeSobreposicao verificacao;

	@Test
	@DisplayName("a constraint da V2 esta no catalogo, com o nome que a verificacao procura")
	void constraintPresenteNoBanco() {
		List<String> exclusoes = this.jdbc.queryForList("""
				SELECT conname FROM pg_constraint
				 WHERE conrelid = 'appointments'::regclass AND contype = 'x'
				""", String.class);

		assertThat(exclusoes)
				.as("""
						se esta lista vier vazia, o container de teste subiu \
						sem a V2 e o ConcurrentBookingHttpTest esta medindo \
						nada -- a garantia contra duplo agendamento vive aqui, \
						nao no codigo""")
				.contains(VerificaAConstraintDeSobreposicao.CONSTRAINT);
	}

	@Test
	@DisplayName("a verificacao do boot passa contra o banco real")
	void verificacaoPassaContraOBancoReal() {
		// O bean ja rodou no @PostConstruct ao subir o contexto -- se tivesse
		// falhado, nenhum teste desta classe existiria. Chamar de novo e o
		// que prova que o SQL esta valido, e nao que ele foi silenciosamente
		// pulado.
		this.verificacao.verificar();
	}

}
