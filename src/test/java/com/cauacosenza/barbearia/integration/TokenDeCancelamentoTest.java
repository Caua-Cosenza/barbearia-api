package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;

import tools.jackson.databind.JsonNode;

/**
 * A vida do token de cancelamento.
 *
 * <h2>O que estes testes fecham</h2>
 *
 * <p>Uma auditoria mediu que o token era <b>credencial de leitura
 * permanente</b>: continuava abrindo a pagina depois de o agendamento ser
 * cancelado, e um token de um agendamento de maio de 2024 ainda devolvia nome,
 * horario, servicos e valor pago. O link viaja por WhatsApp, fica no historico
 * do navegador e sobrevive a qualquer encaminhamento, entao "para sempre" e
 * tempo demais.
 *
 * <p>Duas regras novas, e um invariante que as duas tem de respeitar: a
 * recusa precisa ser <b>indistinguivel</b> da de um token que nunca existiu.
 * Por isso os testes comparam o corpo inteiro, campo a campo, e nao so o
 * status.
 */
class TokenDeCancelamentoTest extends AbstractPostgresIntegrationTest {

	/** Terca, 15/12/2026, 09:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T12:00:00Z");

	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	private static final LocalDate QUARTA = LocalDate.of(2026, 12, 16);

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	private Barber jhonatan;

	private String corte;

	@BeforeEach
	void prepararCatalogo() {
		relogioEm(AGORA);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corte = novoServico("Corte Máquina e Tesoura", 50).getId();

		for (int dia = 2; dia <= 4; dia++) {
			BarberSchedule jornada = new BarberSchedule();
			jornada.setProfessional(this.jhonatan);
			jornada.setDayOfWeek(dia);
			jornada.setStartTime("08:40");
			jornada.setEndTime("19:30");
			this.schedules.saveAndFlush(jornada);
		}
	}

	// =======================================================================
	// O fluxo normal, que nao pode ter mudado
	// =======================================================================

	@Nested
	@DisplayName("o fluxo de boa-fe: recebe o link, abre, cancela")
	class FluxoNormal {

		@Test
		@DisplayName("abre a pagina com os dados e cancela, igual a antes")
		void abreECancela() throws Exception {
			String token = agendar(QUARTA, "10:20");

			JsonNode pagina = corpoDe(consultar(token)).get("data");
			assertThat(pagina.get("guestName").asString()).isEqualTo("Maria Aparecida");
			assertThat(pagina.get("status").asString()).isEqualTo("PENDING");
			assertThat(pagina.get("canCancel").asBoolean()).isTrue();
			assertThat(pagina.get("totalAmountCents").asInt()).isEqualTo(5000);

			assertThat(codigo(cancelar(token))).isEqualTo(200);

			assertThat(appointments.count()).isEqualTo(1);
			assertThat(appointments.findAll().get(0).getStatus())
					.isEqualTo(AppointmentStatus.CANCELLED);
		}

		@Test
		@DisplayName("o nome continua saindo na pagina -- decisao mantida de proposito")
		void nomeContinuaNaPagina() throws Exception {
			// Quem tem o link tem a mensagem do WhatsApp, que ja traz nome,
			// data, servicos e total. Esconder aqui nao protegeria nada e
			// atrapalharia quem esta cancelando.
			String token = agendar(QUARTA, "11:10");

			assertThat(corpoDe(consultar(token)).get("data").get("guestName").asString())
					.isEqualTo("Maria Aparecida");
		}

		@Test
		@DisplayName("abrir a pagina muitas vezes antes de cancelar nao gasta o token")
		void leituraNaoConsomeOToken() throws Exception {
			String token = agendar(QUARTA, "12:00");

			for (int i = 0; i < 5; i++) {
				assertThat(codigo(consultar(token))).isEqualTo(200);
			}
			assertThat(codigo(cancelar(token))).isEqualTo(200);
		}

	}

	// =======================================================================
	// 1. O token morre quando usado
	// =======================================================================

	@Nested
	@DisplayName("depois do cancelamento o token morre")
	class MorreQuandoUsado {

		@Test
		@DisplayName("o GET passa a responder 404, e a coluna fica nula")
		void getRecusado() throws Exception {
			String token = agendar(QUARTA, "10:20");
			assertThat(codigo(consultar(token))).isEqualTo(200);

			cancelar(token);

			assertThat(codigo(consultar(token))).isEqualTo(404);
			// A linha continua la, com o cancelamento registrado.
			assertThat(appointments.findByCancelToken(token)).isEmpty();
			assertThat(jdbc.queryForObject(
					"SELECT \"cancel_token\" IS NULL FROM \"appointments\"", Boolean.class))
							.isTrue();
			assertThat(jdbc.queryForObject(
					"SELECT \"cancelled_at\" IS NOT NULL FROM \"appointments\"", Boolean.class))
							.isTrue();
		}

		@Test
		@DisplayName("a resposta e IGUAL a de um token que nunca existiu")
		void indistinguivelDeInexistente() throws Exception {
			String token = agendar(QUARTA, "10:20");
			cancelar(token);

			JsonNode morto = corpoDe(consultar(token));
			JsonNode nuncaExistiu = corpoDe(consultar(UUID.randomUUID().toString()));

			// Campo a campo, menos o timestamp, que e a hora da resposta.
			assertThat(morto.get("success")).isEqualTo(nuncaExistiu.get("success"));
			assertThat(morto.get("data")).isEqualTo(nuncaExistiu.get("data"));
			assertThat(morto.get("error").asString()).isEqualTo(nuncaExistiu.get("error").asString());
			assertThat(morto.get("message").asString())
					.isEqualTo(nuncaExistiu.get("message").asString())
					.isEqualTo("Agendamento não encontrado");
		}

		@Test
		@DisplayName("o segundo clique em cancelar tambem da 404, nao 400")
		void segundoCliqueEm404() throws Exception {
			// Mudanca de comportamento assumida: antes o status ja nao era
			// cancelavel e saia 400 "Agendamento nao pode ser cancelado".
			// Agora a busca falha antes de qualquer regra de negocio.
			String token = agendar(QUARTA, "10:20");
			cancelar(token);

			ResultActions r = cancelar(token);
			assertThat(codigo(r)).isEqualTo(404);
			assertThat(corpoDe(r).get("message").asString()).isEqualTo("Agendamento não encontrado");
		}

		@Test
		@DisplayName("a hora real do cancelamento nao e sobrescrita por um segundo clique")
		void horaDoCancelamentoPreservada() throws Exception {
			String token = agendar(QUARTA, "10:20");
			cancelar(token);
			Instant primeiro = appointments.findAll().get(0).getCancelledAt();

			relogioEm(Instant.parse("2026-12-15T15:00:00Z"));
			cancelar(token);

			assertThat(appointments.findAll().get(0).getCancelledAt()).isEqualTo(primeiro);
		}

		@Test
		@DisplayName("dois agendamentos cancelados convivem: nulo nao colide no indice unico")
		void doisNulosConvivem() throws Exception {
			String a = agendar(QUARTA, "10:20");
			String b = agendar(QUARTA, "11:10");

			assertThat(codigo(cancelar(a))).isEqualTo(200);
			assertThat(codigo(cancelar(b))).isEqualTo(200);

			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM \"appointments\" WHERE \"cancel_token\" IS NULL",
					Integer.class)).isEqualTo(2);
		}

	}

	// =======================================================================
	// 2. A leitura expira sete dias depois do atendimento
	// =======================================================================

	@Nested
	@DisplayName("a leitura expira 7 dias depois do horario marcado")
	class LeituraExpira {

		@Test
		@DisplayName("no dia seguinte ao atendimento ainda abre")
		void diaSeguinteAbre() throws Exception {
			String token = agendar(QUARTA, "10:20");

			// Atendimento 16/12 10:20; relogio em 17/12.
			relogioEm(Instant.parse("2026-12-17T13:00:00Z"));

			assertThat(codigo(consultar(token))).isEqualTo(200);
		}

		@Test
		@DisplayName("seis dias depois ainda abre")
		void seisDiasAbre() throws Exception {
			String token = agendar(QUARTA, "10:20");

			relogioEm(Instant.parse("2026-12-22T13:00:00Z"));

			assertThat(codigo(consultar(token))).isEqualTo(200);
		}

		@Test
		@DisplayName("um minuto antes dos 7 dias ainda abre")
		void bordaDeDentro() throws Exception {
			String token = agendar(QUARTA, "10:20");

			// 16/12 13:20Z + 7 dias = 23/12 13:20Z. Um minuto antes.
			relogioEm(Instant.parse("2026-12-23T13:19:00Z"));

			assertThat(codigo(consultar(token))).isEqualTo(200);
		}

		@Test
		@DisplayName("um minuto depois dos 7 dias nao abre mais")
		void bordaDeFora() throws Exception {
			String token = agendar(QUARTA, "10:20");

			relogioEm(Instant.parse("2026-12-23T13:21:00Z"));

			assertThat(codigo(consultar(token))).isEqualTo(404);
		}

		@Test
		@DisplayName("um ano depois: 404 igual ao de token inexistente")
		void umAnoDepois() throws Exception {
			String token = agendar(QUARTA, "10:20");

			relogioEm(Instant.parse("2027-12-16T13:00:00Z"));

			ResultActions expirado = consultar(token);
			assertThat(codigo(expirado)).isEqualTo(404);
			assertThat(corpoDe(expirado).get("message").asString())
					.isEqualTo(corpoDe(consultar(UUID.randomUUID().toString()))
							.get("message").asString());
		}

		@Test
		@DisplayName("a janela conta do ATENDIMENTO, nao da criacao")
		void contaDoAtendimento() throws Exception {
			// Agendamento marcado com dois meses de antecedencia: o link
			// precisa abrir nesses dois meses inteiros.
			String token = agendar(LocalDate.of(2027, 2, 16), "10:20");

			relogioEm(Instant.parse("2027-01-15T13:00:00Z"));
			assertThat(codigo(consultar(token))).isEqualTo(200);

			relogioEm(Instant.parse("2027-02-16T12:00:00Z"));
			assertThat(codigo(consultar(token))).isEqualTo(200);
		}

		@Test
		@DisplayName("a expiracao vale so para a LEITURA: o POST continua barrado pelo prazo de 1h")
		void escritaSegueComOPrazoDeUmaHora() throws Exception {
			String token = agendar(QUARTA, "10:20");

			// Meia hora antes do atendimento: dentro da janela de leitura,
			// fora do prazo de cancelamento.
			relogioEm(Instant.parse("2026-12-16T12:50:00Z"));

			assertThat(codigo(consultar(token))).isEqualTo(200);
			assertThat(corpoDe(consultar(token)).get("data").get("canCancel").asBoolean())
					.isFalse();

			ResultActions r = cancelar(token);
			assertThat(codigo(r)).isEqualTo(400);
			assertThat(corpoDe(r).get("message").asString())
					.isEqualTo("Prazo de cancelamento encerrado. "
							+ "Entre em contato com a barbearia.");
			// E o token NAO morreu: a escusa foi recusada, nada foi consumido.
			assertThat(appointments.findByCancelToken(token)).isPresent();
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private void relogioEm(Instant quando) {
		BDDMockito.given(this.clock.instant()).willReturn(quando);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);
	}

	private String agendar(LocalDate dia, String hora) throws Exception {
		JsonNode criado = corpoDe(this.mvc.perform(postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": ["%s"],
				  "scheduledAt": "%sT%s:00-03:00",
				  "guestName": "Maria Aparecida",
				  "guestPhone": "5522999990000"
				}
				""".formatted(this.jhonatan.getId(), this.corte, dia, hora))));

		assertThat(criado.get("success").asBoolean())
				.as("criacao falhou: %s", criado).isTrue();
		return criado.get("data").get("cancelToken").asString();
	}

	private ResultActions consultar(String token) throws Exception {
		return this.mvc.perform(getComIpProprio("/api/v1/cancel/" + token));
	}

	private ResultActions cancelar(String token) throws Exception {
		return this.mvc.perform(postJson("/api/v1/cancel/" + token, "{}"));
	}

	private static int codigo(ResultActions r) throws Exception {
		return r.andReturn().getResponse().getStatus();
	}

	private JsonNode corpoDe(ResultActions resultado) throws Exception {
		return this.json.readTree(
				resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

}
