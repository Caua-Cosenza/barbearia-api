package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.repository.AppointmentRepository;

import tools.jackson.databind.JsonNode;

/**
 * As duas recusas do banco viram 409, deterministicamente.
 *
 * <p><b>A rede embaixo do {@link ConcurrentBookingHttpTest}.</b> Aquele teste
 * prova que a corrida <i>real</i> funciona, e continua sendo a prova que
 * importa — mas a forma como ele mata o ramo do deadlock e
 * <b>probabilistica</b>: depende de o Postgres escolher matar uma das duas
 * transacoes em alguma das 20 repeticoes. A medicao por mutante mostrou que o
 * {@code catch} de {@code PessimisticLockingFailureException} era morto por
 * ele e por mais nenhuma classe, e esse e justamente o ramo que cobre 18 de
 * cada 20 disputas reais. Um teste sorteado e melhor do que nenhum, e nao e
 * ancoragem.
 *
 * <p>Aqui a excecao vem de um espiao no repositorio, entao nao ha sorteio:
 * {@code saveAndFlush} lanca o que se pedir, sempre. O resto do caminho e
 * real — controller, validacoes, traducao no service,
 * {@code GlobalExceptionHandler}, serializacao do envelope.
 *
 * <p><b>Por que {@link MockitoSpyBean} e nao {@link MockitoBean}.</b> Um mock
 * do repositorio devolveria nulo nas oito consultas que a criacao faz antes de
 * gravar, e o pedido morreria numa validacao antes de chegar ao
 * {@code saveAndFlush} — o teste passaria a verificar outra coisa. O espiao
 * deixa todo o resto ir ao banco de verdade e troca so a gravacao.
 */
class RecusaDoBancoViraConflitoTest extends AbstractPostgresIntegrationTest {

	/** 2026-12-15, terca-feira, 03:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	private static final String MENSAGEM_DE_NEGOCIO =
			"Este horário acabou de ser reservado. Escolha outro.";

	@MockitoBean(name = "clock")
	private Clock clock;

	/** O repositorio real, com {@code saveAndFlush} sob controle do teste. */
	@MockitoSpyBean
	private AppointmentRepository espiao;

	@Autowired
	private MockMvc mvc;

	private Barber jhonatan;

	private String corteId;

	@BeforeEach
	void prepararAgenda() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corteId = novoServico("Corte", 50).getId();

		// dayOfWeek 2 porque 2026-12-15 e terca, na convencao do banco
		// (0 = domingo).
		BarberSchedule terca = new BarberSchedule();
		terca.setProfessional(this.jhonatan);
		terca.setDayOfWeek(2);
		terca.setStartTime("08:40");
		terca.setEndTime("19:30");
		this.schedules.saveAndFlush(terca);
	}

	/**
	 * O caso que a corrida real produz em 18 de cada 20 disputas: as duas
	 * transacoes chegaram ao indice ainda abertas, cada uma esperou pela
	 * outra, e o Postgres matou uma.
	 *
	 * <p>{@code CannotAcquireLockException} e subclasse de
	 * {@code PessimisticLockingFailureException}, que e o que o service
	 * captura — usar a subclasse concreta e de proposito: e a que o tradutor
	 * de excecoes do Spring realmente entrega.
	 */
	@Test
	@DisplayName("deadlock no banco vira 409 com a mensagem de negocio")
	void deadlockViraConflito() throws Exception {
		willThrow(new CannotAcquireLockException("deadlock detected"))
				.given(this.espiao).saveAndFlush(any());

		conferirConflito();
	}

	/** A outra face: o vencedor da corrida ja tinha comitado. */
	@Test
	@DisplayName("violacao de integridade vira 409 com a mensagem de negocio")
	void violacaoDeIntegridadeViraConflito() throws Exception {
		willThrow(new DataIntegrityViolationException(
				"ERROR: conflicting key value violates exclusion constraint"))
				.given(this.espiao).saveAndFlush(any());

		conferirConflito();
	}

	// =======================================================================

	/**
	 * O que o cliente recebe, campo a campo.
	 *
	 * <p>Nao basta afirmar 409: o mutante que troca o status traduzido e so um
	 * dos dois que importam, e o outro e o ramo do {@code catch}. Afirmar
	 * tambem a mensagem e o {@code error} garante que a traducao passou pela
	 * {@code SlotIndisponivelException} e nao por outro caminho que
	 * coincidentemente devolve 409.
	 */
	private void conferirConflito() throws Exception {
		var resposta = this.mvc.perform(postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": ["%s"],
				  "scheduledAt": "2026-12-15T10:20:00-03:00",
				  "guestName": "Maria Aparecida",
				  "guestPhone": "5522999990000"
				}
				""".formatted(this.jhonatan.getId(), this.corteId)))
				.andReturn().getResponse();

		assertThat(resposta.getStatus())
				.as("recusa do banco tem que virar 409, nunca 500")
				.isEqualTo(409);

		JsonNode corpo = this.json.readTree(
				resposta.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

		assertThat(corpo.get("success").asBoolean()).isFalse();
		assertThat(corpo.get("error").asString()).isEqualTo("Conflict");
		assertThat(corpo.get("message").asString()).isEqualTo(MENSAGEM_DE_NEGOCIO);

		// O erro do Postgres fica no log, nao na resposta.
		assertThat(corpo.get("message").asString())
				.doesNotContain("deadlock", "constraint", "exclusion", "SQL", "ERROR");
	}

}
