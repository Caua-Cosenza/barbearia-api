package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;

import tools.jackson.databind.JsonNode;

/**
 * Duas requisicoes HTTP simultaneas para o mesmo horario.
 *
 * <p>O {@code OverlapRaceConditionTest} da Fase 4 provou que o <b>banco</b>
 * recusa o segundo agendamento. Este prova o que o <b>cliente</b> recebe
 * quando isso acontece — que e outra coisa, e e a que importa para o site.
 *
 * <h2>Por que este teste existe</h2>
 *
 * <p>Na Fase 4 ficou claro que o perdedor da corrida recebe
 * {@code DataIntegrityViolationException} ou {@code CannotAcquireLockException}
 * (deadlock), e que o segundo caso e o mais comum: 18 de 20 iteracoes. Se o
 * service traduzisse apenas o primeiro, o deadlock cairia no catch-all do
 * {@code GlobalExceptionHandler} e o cliente veria <b>500 "Erro inesperado"</b>
 * na maioria das disputas reais por horario — falha de sistema onde deveria
 * haver "esse horario acabou de ser reservado".
 *
 * <p>Dai a asercao mais importante daqui: <b>nenhuma das 20 execucoes pode
 * produzir 500</b>. Um unico 500 significa que ha um caminho de recusa do
 * banco sem traducao, e e exatamente o tipo de falha que nao aparece em teste
 * sequencial nem em uso manual.
 */
class ConcurrentBookingHttpTest extends AbstractPostgresIntegrationTest {

	/** 2026-12-15, <b>terca-feira</b>, 03:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	private static final String HORARIO_DISPUTADO = "2026-12-15T10:20:00-03:00";

	private static final int TIMEOUT_SEGUNDOS = 30;

	@MockitoBean(name = "clock")
	private Clock clock;

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
		// (0 = domingo). Registrar 1 por engano faz toda a disputa virar
		// "Profissional nao atende neste dia" e o teste falhar por um motivo
		// que nada tem a ver com concorrencia.
		BarberSchedule terca = new BarberSchedule();
		terca.setProfessional(this.jhonatan);
		terca.setDayOfWeek(2);
		terca.setStartTime("08:40");
		terca.setEndTime("19:30");
		this.schedules.saveAndFlush(terca);
	}

	@RepeatedTest(value = 20, name = "iteracao {currentRepetition} de {totalRepetitions}")
	@DisplayName("duas requisicoes, um horario: 201 e 409 — nunca 500")
	void umaCriaOutraRecebe409() throws Exception {
		List<Resposta> respostas = disputar(2);

		assertThat(respostas).extracting(Resposta::status)
				.as("respostas: %s", respostas)
				.containsExactlyInAnyOrder(201, 409);

		Resposta perdedora = respostas.stream()
				.filter(resposta -> resposta.status() == 409)
				.findFirst()
				.orElseThrow();

		// Mensagem de negocio, nao vazamento do erro do Postgres.
		assertThat(perdedora.mensagem())
				.isEqualTo("Este horário acabou de ser reservado. Escolha outro.");
		assertThat(perdedora.erro()).isEqualTo("Conflict");
		assertThat(perdedora.mensagem()).doesNotContain("deadlock", "constraint", "SQL");

		// A asercao que fecha tudo: uma linha no banco, nao duas.
		assertThat(appointments.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("oito requisicoes simultaneas: uma 201, sete 409, nenhum 500")
	void oitoRequisicoes() throws Exception {
		List<Resposta> respostas = disputar(8);

		assertThat(respostas).filteredOn(resposta -> resposta.status() == 201).hasSize(1);
		assertThat(respostas).filteredOn(resposta -> resposta.status() == 409).hasSize(7);
		assertThat(respostas).noneMatch(resposta -> resposta.status() >= 500);
		assertThat(appointments.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("horarios encostados em paralelo: todas recebem 201")
	void horariosDiferentesNaoDisputam() throws Exception {
		// O contraponto: a garantia nao pode serializar o que nao conflita.
		List<String> horarios = List.of(
				"2026-12-15T09:30:00-03:00",
				"2026-12-15T10:20:00-03:00",
				"2026-12-15T11:10:00-03:00",
				"2026-12-15T12:00:00-03:00");

		CountDownLatch largada = new CountDownLatch(1);
		List<Callable<Resposta>> tentativas = new ArrayList<>();
		for (String horario : horarios) {
			tentativas.add(tentativa(horario, largada));
		}

		List<Resposta> respostas = executar(tentativas, largada);

		assertThat(respostas).allMatch(resposta -> resposta.status() == 201);
		assertThat(appointments.count()).isEqualTo(horarios.size());
	}

	// =======================================================================
	// Mecanica
	// =======================================================================

	/** O que uma requisicao devolveu, ja desembrulhado do envelope. */
	private record Resposta(int status, String erro, String mensagem) {

		@Override
		public String toString() {
			return this.status + " " + this.erro + " / " + this.mensagem;
		}

	}

	private List<Resposta> disputar(int quantidade) throws Exception {
		CountDownLatch largada = new CountDownLatch(1);
		List<Callable<Resposta>> tentativas = new ArrayList<>(quantidade);
		for (int i = 0; i < quantidade; i++) {
			tentativas.add(tentativa(HORARIO_DISPUTADO, largada));
		}
		return executar(tentativas, largada);
	}

	/**
	 * Uma requisicao HTTP completa, disparada na largada.
	 *
	 * <p>O corpo e montado antes do {@code await} para que depois da largada
	 * sobre apenas a chamada. Cada tentativa leva um nome de visitante
	 * diferente, o que nao muda nada na disputa mas deixa claro no banco qual
	 * delas venceu.
	 */
	private Callable<Resposta> tentativa(String scheduledAt, CountDownLatch largada) {
		String corpo = """
				{
				  "professionalId": "%s",
				  "serviceIds": ["%s"],
				  "scheduledAt": "%s",
				  "guestName": "Cliente %s",
				  "guestPhone": "5522999990000"
				}
				""".formatted(this.jhonatan.getId(), this.corteId, scheduledAt,
				Integer.toHexString(scheduledAt.hashCode()));

		return () -> {
			largada.await(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);

			var resultado = this.mvc.perform(postJson("/api/v1/appointments/public", corpo))
					.andReturn().getResponse();

			JsonNode envelope = this.json.readTree(
					resultado.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

			return new Resposta(
					resultado.getStatus(),
					texto(envelope, "error"),
					texto(envelope, "message"));
		};
	}

	private static String texto(JsonNode envelope, String campo) {
		JsonNode valor = envelope.get(campo);
		return (valor == null || valor.isNull()) ? null : valor.asString();
	}

	private List<Resposta> executar(List<Callable<Resposta>> tentativas, CountDownLatch largada)
			throws Exception {

		List<Resposta> respostas = new ArrayList<>(tentativas.size());

		try (ExecutorService executor = Executors.newFixedThreadPool(tentativas.size())) {
			List<Future<Resposta>> futuros = new ArrayList<>(tentativas.size());
			for (Callable<Resposta> tentativa : tentativas) {
				futuros.add(executor.submit(tentativa));
			}

			// Todas ja estao no await: a largada e simultanea.
			largada.countDown();

			for (Future<Resposta> futuro : futuros) {
				respostas.add(futuro.get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS));
			}

			executor.shutdown();
			if (!executor.awaitTermination(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)) {
				fail("as threads da disputa nao terminaram");
			}
		}

		// Vale para toda rodada, inclusive as de horarios distintos: nenhum
		// caminho de recusa do banco pode chegar ao cliente como 500.
		assertThat(respostas)
				.as("nenhuma resposta pode ser 5xx: %s", respostas)
				.noneMatch(resposta -> resposta.status() >= 500);

		return respostas;
	}

}
