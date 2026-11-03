package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

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

import tools.jackson.databind.JsonNode;

/**
 * O que a API barra, e o que ela aceita, quando o pedido NAO vem da tela.
 *
 * <h2>Por que este teste existe</h2>
 *
 * <p>O frontend esconde dia fechado, oferece so pontos de grade e calcula a
 * duracao a partir dos servicos escolhidos. Nada disso e validacao: e
 * interface. Quem manda {@code POST /api/v1/appointments/public} com
 * {@code curl} nao passa por tela nenhuma, e o que vale e o que o backend
 * recusa.
 *
 * <p>Estes testes sao de <b>caracterizacao</b>: afirmam o comportamento de
 * hoje, inclusive onde ele e discutivel. Dois casos estao marcados como
 * CANARIO porque passam hoje e talvez nao devessem — se alguem decidir
 * fecha-los, o teste quebra e a decisao fica visivel no diff em vez de
 * silenciosa.
 *
 * <h2>O relogio</h2>
 *
 * <p>Fixo numa <b>segunda</b> as 09:00 de Brasilia. A escolha nao e
 * decorativa: com o relogio parado numa terca as 03:00, o caso "terca as
 * 03:00, fora do expediente" cairia primeiro na validacao de data futura e
 * mediria a validacao errada.
 */
class ValidacaoDeAgendamentoPorHttpTest extends AbstractPostgresIntegrationTest {

	/** Segunda, 14/12/2026, 09:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-14T12:00:00Z");

	/** Segunda seguinte. Sem linha em {@code professional_availability}. */
	private static final LocalDate SEGUNDA = LocalDate.of(2026, 12, 21);

	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	private static final LocalDate QUARTA = LocalDate.of(2026, 12, 16);

	/** Sexta. Tambem sem jornada: o controle do caso de dia fechado. */
	private static final LocalDate SEXTA = LocalDate.of(2026, 12, 18);

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	private Barber jhonatan;

	private String corteId;

	private String barbaId;

	@BeforeEach
	void prepararCatalogo() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corteId = novoServico("Corte", 50).getId();
		this.barbaId = novoServico("Barba", 10).getId();

		// TERCA a QUINTA (dayOfWeek 2, 3, 4), 08:40-19:30. Segunda de fora de
		// proposito: e o estado atual do banco de desenvolvimento, onde a
		// linha de dayOfWeek=1 foi removida, e e o que torna o caso 1
		// verificavel.
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
	// 1. Dia sem expediente
	// =======================================================================

	@Nested
	@DisplayName("dia sem jornada cadastrada")
	class DiaFechado {

		@Test
		@DisplayName("segunda e recusada com 400, mesmo em horario comercial")
		void segundaRecusada() throws Exception {
			agendar(SEGUNDA, "10:00", corteId)
					.andExpect(status().isBadRequest());

			assertThat(mensagemDe(agendar(SEGUNDA, "10:00", corteId)))
					.isEqualTo("Profissional não atende neste dia");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("sexta tambem, pelo mesmo caminho")
		void sextaRecusada() throws Exception {
			// Controle: a recusa nao e uma peculiaridade da segunda, e a
			// ausencia de linha para aquele dayOfWeek.
			assertThat(mensagemDe(agendar(SEXTA, "10:00", corteId)))
					.isEqualTo("Profissional não atende neste dia");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("a terca, no mesmo horario, e aceita")
		void tercaAceita() throws Exception {
			// Sem este controle, os dois testes acima passariam tambem se a
			// criacao estivesse quebrada para TODO dia.
			agendar(TERCA, "10:00", corteId).andExpect(status().isCreated());
			assertThat(appointments.count()).isEqualTo(1);
		}

	}

	// =======================================================================
	// 2. Fora do expediente
	// =======================================================================

	@Nested
	@DisplayName("fora da janela de 08:40 as 19:30")
	class ForaDoExpediente {

		@Test
		@DisplayName("03:00 da manha e recusado com 400")
		void madrugadaRecusada() throws Exception {
			assertThat(mensagemDe(agendar(TERCA, "03:00", corteId)))
					.isEqualTo("Horário fora do expediente");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("23:00 tambem")
		void noiteRecusada() throws Exception {
			assertThat(mensagemDe(agendar(TERCA, "23:00", corteId)))
					.isEqualTo("Horário fora do expediente");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("comeca dentro mas termina depois do fecho: recusado, e a mensagem diz as horas")
		void transbordaOFecho() throws Exception {
			// 19:20 + 50 min = 20:10. A mensagem nomeia os dois horarios, o
			// que e a diferenca entre "nao deu" e saber escolher outro.
			assertThat(mensagemDe(agendar(TERCA, "19:20", corteId)))
					.isEqualTo("O atendimento terminaria às 20:10 e o expediente "
							+ "encerra às 19:30. Escolha um horário mais cedo.");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("08:40 em ponto, a abertura, e aceito")
		void aberturaAceita() throws Exception {
			agendar(TERCA, "08:40", corteId).andExpect(status().isCreated());
		}

		@Test
		@DisplayName("18:40, o ultimo que cabe inteiro, e aceito")
		void ultimoQueCabe() throws Exception {
			// 18:40 + 50 = 19:30, encostado no fecho. Encostar nao transborda.
			agendar(TERCA, "18:40", corteId).andExpect(status().isCreated());
		}

	}

	// =======================================================================
	// 3. Fora da grade -- CANARIO
	// =======================================================================

	@Nested
	@DisplayName("horario que nao e ponto de grade")
	class ForaDaGrade {

		@Test
		@DisplayName("CANARIO: 09:37 e ACEITO hoje, embora a grade ofereca 08:40, 09:30, 10:20...")
		void horarioQuebradoEhAceito() throws Exception {
			// Nenhuma validacao compara o inicio pedido com os pontos que o
			// SlotCalculator geraria. O backend checa jornada, transbordo,
			// bloqueio e sobreposicao -- e 09:37 passa em todos.
			//
			// Se alguem decidir exigir alinhamento de grade, este teste
			// quebra, e e isso que ele serve para provocar.
			JsonNode corpo = corpoDe(agendar(TERCA, "09:37", corteId)
					.andExpect(status().isCreated()));

			String id = corpo.get("data").get("id").asString();
			assertThat(appointments.findById(id).orElseThrow().getSlotDurationMinutes())
					.isEqualTo(50);
		}

		@Test
		@DisplayName("os segundos enviados pelo cliente sao truncados no minuto")
		void segundosSaoTruncados() throws Exception {
			// 09:37:29 e gravado como 09:37:00. O horario quebrado continua
			// aceito -- isso e outro canario --, mas o segundo nao sobrevive.
			JsonNode corpo = corpoDe(agendar(QUARTA, "09:37:29", corteId)
					.andExpect(status().isCreated()));

			assertThat(jdbc.queryForObject(
					"SELECT to_char(\"scheduled_at\", 'HH24:MI:SS') FROM \"appointments\"",
					String.class)).isEqualTo("12:37:00");

			// A resposta tambem: o cliente ve o que foi reservado.
			assertThat(corpo.get("data").get("scheduledAt").asString())
					.isEqualTo("2026-12-16T12:37:00.000Z");
		}

		@Test
		@DisplayName("fracao de segundo tambem desaparece")
		void fracaoDeSegundoTambem() throws Exception {
			agendar(QUARTA, "09:37:29.876", corteId).andExpect(status().isCreated());

			assertThat(jdbc.queryForObject(
					"SELECT to_char(\"scheduled_at\", 'HH24:MI:SS.MS') FROM \"appointments\"",
					String.class)).isEqualTo("12:37:00.000");
		}

		@Test
		@DisplayName("dois pedidos no mesmo minuto, com segundos diferentes, colidem")
		void mesmoMinutoColide() throws Exception {
			// Este e o motivo de truncar. Com o segundo preservado, as janelas
			// [10:00:00, 10:50:00) e [10:00:30, 10:50:30) se sobrepoem e a
			// constraint pegaria -- mas um pedido as 10:50:00 e outro as
			// 10:00:30 NAO se sobrepoem pela borda, e dois agendamentos
			// entrariam em minutos que a grade trata como o mesmo slot.
			agendar(TERCA, "10:00:00", corteId).andExpect(status().isCreated());

			assertThat(mensagemDe(agendar(TERCA, "10:00:30", corteId)))
					.isEqualTo("Este horário acabou de ser reservado. Escolha outro.");
			assertThat(appointments.count()).isEqualTo(1);
		}

		@Test
		@DisplayName("um horario quebrado ocupa a agenda e bloqueia os vizinhos")
		void quebradoOcupaAgenda() throws Exception {
			// A consequencia pratica de aceitar fora da grade: 09:37 reserva
			// de 09:37 a 10:27, e o slot legitimo das 10:20 some da lista.
			agendar(TERCA, "09:37", corteId).andExpect(status().isCreated());

			assertThat(horariosDe(TERCA)).doesNotContain("10:20");
			assertThat(horariosDe(TERCA)).contains("08:40");
		}

	}

	// =======================================================================
	// 4. Duracao e preco informados pelo cliente
	// =======================================================================

	@Nested
	@DisplayName("duracao e preco nao sao do cliente")
	class DuracaoEPreco {

		@Test
		@DisplayName("slotDurationMinutes no corpo: 400, o campo nao existe no DTO")
		void campoDeDuracaoNaoExiste() throws Exception {
			// FAIL_ON_UNKNOWN_PROPERTIES, do JacksonConfig, e o equivalente do
			// .strict() do Zod: campo a mais derruba a desserializacao inteira
			// antes de qualquer regra de negocio.
			assertThat(mensagemDe(agendarCom(corpoCruCom(TERCA, "10:00", "\"slotDurationMinutes\": 1"))))
					.isEqualTo("Corpo da requisição inválido");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("totalDurationMinutes zero ou negativo: 400 pelo mesmo motivo")
		void duracaoAbsurdaNaoEntra() throws Exception {
			// O nome existe em AvailableTimesRequest, da consulta de horarios.
			// Na criacao, nao: aqui ele e so um campo desconhecido.
			assertThat(mensagemDe(agendarCom(corpoCruCom(TERCA, "10:00", "\"totalDurationMinutes\": 0"))))
					.isEqualTo("Corpo da requisição inválido");
			assertThat(mensagemDe(agendarCom(corpoCruCom(TERCA, "10:00", "\"totalDurationMinutes\": -99"))))
					.isEqualTo("Corpo da requisição inválido");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("amountCents no corpo: 400")
		void precoDoClienteNaoEntra() throws Exception {
			assertThat(mensagemDe(agendarCom(corpoCruCom(TERCA, "10:00", "\"amountCents\": 1"))))
					.isEqualTo("Corpo da requisição inválido");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("o que e gravado vem do catalogo: soma dos servicos, capada em 50")
		void valoresVemDoCatalogo() throws Exception {
			// Corte 50 + Barba 10 = 60 bruto, 5000 + 5000 = 10000 centavos.
			JsonNode corpo = corpoDe(agendar(TERCA, "10:00", corteId, barbaId)
					.andExpect(status().isCreated()));

			String id = corpo.get("data").get("id").asString();
			var gravado = appointments.findById(id).orElseThrow();

			assertThat(gravado.getAmountCents()).isEqualTo(10000);
			// 60 bruto, 50 no banco: e a duracao que ocupa a agenda.
			assertThat(gravado.getSlotDurationMinutes()).isEqualTo(50);
			// A resposta expoe o bruto, sem o teto -- igual ao Node.
			assertThat(corpo.get("data").get("totalDurationMinutes").asInt()).isEqualTo(60);
		}

	}

	// =======================================================================
	// 5. Servico inexistente ou repetido
	// =======================================================================

	@Nested
	@DisplayName("servicos pedidos")
	class Servicos {

		private static final String FANTASMA = "11111111-2222-4333-8444-555555555555";

		@Test
		@DisplayName("id que nao existe: 404, e a mensagem cita o id")
		void inexistenteRecusado() throws Exception {
			assertThat(mensagemDe(agendar(TERCA, "10:00", FANTASMA)))
					.isEqualTo("Service not found: " + FANTASMA);
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("um id valido junto de um invalido: 404, nada e criado")
		void umInvalidoDerrubaTudo() throws Exception {
			assertThat(mensagemDe(agendar(TERCA, "10:00", corteId, FANTASMA)))
					.isEqualTo("Service not found: " + FANTASMA);
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("o mesmo servico cinco vezes conta UMA: R$ 50,00, nao R$ 250,00")
		void repetidoContaUmaVez() throws Exception {
			// O numero que importa e o amountCents: e ele que vai no
			// "Total:" da mensagem de WhatsApp que o cliente le na
			// confirmacao. Cobrar cinco vezes por um corte seria o pior
			// defeito possivel deste endpoint.
			JsonNode corpo = corpoDe(agendar(TERCA, "10:00",
					corteId, corteId, corteId, corteId, corteId)
							.andExpect(status().isCreated()));

			String id = corpo.get("data").get("id").asString();
			var gravado = appointments.findById(id).orElseThrow();

			assertThat(gravado.getAmountCents()).isEqualTo(5000);
			assertThat(gravado.getSlotDurationMinutes()).isEqualTo(50);
			assertThat(corpo.get("data").get("totalDurationMinutes").asInt()).isEqualTo(50);

			// Uma linha, nao cinco.
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM \"appointment_services\" WHERE \"appointment_id\" = ?",
					Integer.class, id)).isEqualTo(1);
			assertThat(corpo.get("data").get("services")).hasSize(1);
		}

		@Test
		@DisplayName("repeticao de servico curto nao infla a agenda")
		void repetidoNaoInflaADuracao() throws Exception {
			// Com servico curto o teto de 50 nao mascararia nada: antes,
			// 5 x Barba (10 min) ocupavam 50 minutos de agenda por um pedido
			// que a tela ofereceria como 10.
			JsonNode corpo = corpoDe(agendar(TERCA, "10:00",
					barbaId, barbaId, barbaId, barbaId, barbaId)
							.andExpect(status().isCreated()));

			String id = corpo.get("data").get("id").asString();
			assertThat(appointments.findById(id).orElseThrow().getSlotDurationMinutes())
					.isEqualTo(10);
			assertThat(corpo.get("data").get("totalDurationMinutes").asInt()).isEqualTo(10);
			assertThat(appointments.findById(id).orElseThrow().getAmountCents()).isEqualTo(5000);
		}

		@Test
		@DisplayName("a deduplicacao preserva a ordem do primeiro aparecimento")
		void deduplicacaoPreservaAOrdem() throws Exception {
			// barba, corte, barba -> [barba, corte]. A ordem decide qual
			// servico fica em appointments.service_id, a coluna NOT NULL
			// herdada de quando um agendamento tinha um servico so.
			JsonNode corpo = corpoDe(agendar(TERCA, "10:00", barbaId, corteId, barbaId)
					.andExpect(status().isCreated()));

			assertThat(corpo.get("data").get("services").valueStream()
					.map(no -> no.get("name").asString()).toList())
							.containsExactly("Barba", "Corte");

			String id = corpo.get("data").get("id").asString();
			assertThat(jdbc.queryForObject(
					"SELECT \"service_id\" FROM \"appointments\" WHERE \"id\" = ?",
					String.class, id)).isEqualTo(barbaId);
			// 10 + 50 = 60 bruto, capado em 50 na agenda.
			assertThat(corpo.get("data").get("totalDurationMinutes").asInt()).isEqualTo(60);
			assertThat(appointments.findById(id).orElseThrow().getAmountCents()).isEqualTo(10000);
		}

	}

	// =======================================================================
	// 6. Passado
	// =======================================================================

	@Nested
	@DisplayName("data no passado")
	class Passado {

		@Test
		@DisplayName("ontem: 422 na validacao do DTO, antes de qualquer regra")
		void ontemRecusado() throws Exception {
			// 422 e nao 400: e @DataFutura, validacao de bean, e o
			// GlobalExceptionHandler mapeia violacao de constraint para 422 --
			// o mesmo status que o Zod do Node devolve.
			assertThat(mensagemDe(agendar(LocalDate.of(2026, 12, 13), "10:00", corteId)))
					.isEqualTo("scheduledAt: Data deve ser no futuro");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("ano passado: mesma recusa")
		void anoPassadoRecusado() throws Exception {
			assertThat(mensagemDe(agendar(LocalDate.of(2025, 12, 16), "10:00", corteId)))
					.isEqualTo("scheduledAt: Data deve ser no futuro");
			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("hoje mais cedo, ja passado, tambem")
		void hojeMaisCedoRecusado() throws Exception {
			// O relogio esta parado as 09:00 de uma segunda. 08:00 do mesmo
			// dia e passado -- e segunda ainda e dia fechado, entao este caso
			// prova a ORDEM: a validacao de data vem antes da de jornada.
			assertThat(mensagemDe(agendar(LocalDate.of(2026, 12, 14), "08:00", corteId)))
					.isEqualTo("scheduledAt: Data deve ser no futuro");
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private ResultActions agendar(LocalDate dia, String hora, String... servicoIds)
			throws Exception {

		return agendarCom(corpoCru(dia, hora, servicoIds, null));
	}

	private ResultActions agendarCom(String corpoJson) throws Exception {
		return this.mvc.perform(postJson("/api/v1/appointments/public", corpoJson));
	}

	/** O corpo valido, mais um campo extra que o DTO nao conhece. */
	private String corpoCruCom(LocalDate dia, String hora, String campoExtra) {
		return corpoCru(dia, hora, new String[] { this.corteId }, campoExtra);
	}

	private String corpoCru(LocalDate dia, String hora, String[] servicoIds, String campoExtra) {
		String ids = String.join(", ",
				java.util.Arrays.stream(servicoIds).map(id -> "\"" + id + "\"").toList());

		// A hora pode vir com segundos ("09:37:29") ou sem ("10:00").
		String horaCompleta = (hora.length() == 5) ? hora + ":00" : hora;

		String extra = (campoExtra == null) ? "" : ("  " + campoExtra + ",\n");

		return """
				{
				%s  "professionalId": "%s",
				  "serviceIds": [%s],
				  "scheduledAt": "%sT%s-03:00",
				  "guestName": "Maria Aparecida",
				  "guestPhone": "5522999990000"
				}
				""".formatted(extra, this.jhonatan.getId(), ids, dia, horaCompleta);
	}

	private java.util.List<String> horariosDe(LocalDate dia) throws Exception {
		JsonNode corpo = corpoDe(this.mvc.perform(postJson("/api/v1/available-times", """
				{
				  "professionalId": "%s",
				  "serviceId": "%s",
				  "date": "%s",
				  "totalDurationMinutes": 50
				}
				""".formatted(this.jhonatan.getId(), this.corteId, dia))));

		return corpo.get("data").get("slots").valueStream().map(JsonNode::asString).toList();
	}

	private String mensagemDe(ResultActions resultado) throws Exception {
		return corpoDe(resultado).get("message").asString();
	}

	private JsonNode corpoDe(ResultActions resultado) throws Exception {
		return this.json.readTree(
				resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

}
