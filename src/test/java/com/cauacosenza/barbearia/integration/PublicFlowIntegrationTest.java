package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;

import tools.jackson.databind.JsonNode;

/**
 * O fluxo publico de ponta a ponta: HTTP, Spring, JPA, Postgres de verdade.
 *
 * <p>Os testes de controller provam o contrato com os services mockados; estes
 * provam que o contrato continua valendo quando quem responde e o banco. E a
 * unica camada em que as oito validacoes de criacao, a constraint de exclusao
 * e o calculo de horarios aparecem juntos.
 *
 * <p><b>O relogio e fixo.</b> Tres regras dependem do instante atual — a
 * antecedencia de 15 minutos, a recusa de passado e o prazo de uma hora para
 * cancelar — e sem um {@link Clock} controlado os testes de borda seriam
 * intermitentes ou impossiveis. A data escolhida, 2026-12-15, e uma
 * <b>segunda-feira</b>: dia com jornada cadastrada, ao contrario de sexta e
 * sabado, que sao de ordem de chegada.
 */
class PublicFlowIntegrationTest extends AbstractPostgresIntegrationTest {

	/** Terca-feira: dia com jornada cadastrada. */
	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	/** Sexta-feira: sem jornada, e por isso ordem de chegada. */
	private static final LocalDate SEXTA = LocalDate.of(2026, 12, 18);

	/**
	 * 2026-12-15 03:00 em Brasilia — madrugada da propria terca.
	 *
	 * <p>Antes da abertura de proposito: assim todo horario do expediente
	 * daquele dia esta no futuro, e os testes de jornada nao esbarram na
	 * recusa de agendamento no passado. Os testes de antecedencia minima
	 * avancam o relogio por conta propria.
	 */
	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	/**
	 * O relogio da aplicacao, fixo. {@code @MockitoBean} substitui o bean do
	 * {@code ClockConfig} no contexto inteiro, inclusive dentro dos services.
	 */
	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	private Barber jhonatan;

	private String corteId;

	private String barbaId;

	@BeforeEach
	void prepararCatalogo() {
		org.mockito.BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		org.mockito.BDDMockito.given(this.clock.getZone()).willReturn(java.time.ZoneOffset.UTC);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corteId = novoServico("Corte", 50).getId();
		this.barbaId = novoServico("Barba", 10).getId();

		// Segunda a quinta, 08:40-19:30 -- a jornada real do seed.
		for (int dia = 1; dia <= 4; dia++) {
			BarberSchedule jornada = new BarberSchedule();
			jornada.setProfessional(this.jhonatan);
			jornada.setDayOfWeek(dia);
			jornada.setStartTime("08:40");
			jornada.setEndTime("19:30");
			this.schedules.saveAndFlush(jornada);
		}
	}

	// =======================================================================
	// O fluxo completo
	// =======================================================================

	@Nested
	@DisplayName("consultar horarios, agendar, consultar de novo")
	class FluxoCompleto {

		@Test
		@DisplayName("o horario agendado desaparece da lista; os outros continuam la")
		void horarioAgendadoSomeDaLista() throws Exception {
			List<String> antes = consultarHorarios(TERCA, corteId, 50);

			assertThat(antes).contains("08:40", "09:30", "10:20");
			int quantidadeAntes = antes.size();

			agendar(TERCA, "10:20", List.of(corteId))
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isCreated());

			List<String> depois = consultarHorarios(TERCA, corteId, 50);

			assertThat(depois).doesNotContain("10:20");
			// 09:30 continua: termina 10:20, encostado no agendamento.
			assertThat(depois).contains("08:40", "09:30");
			assertThat(depois).hasSize(quantidadeAntes - 1);
		}

		@Test
		@DisplayName("o agendamento criado esta no banco, com PII cifrada e token")
		void agendamentoGravadoCorretamente() throws Exception {
			JsonNode criado = corpoDe(agendar(TERCA, "10:20", List.of(corteId, barbaId)));

			String id = criado.get("data").get("id").asString();
			Appointment gravado = appointments.findById(id).orElseThrow();

			assertThat(gravado.getStatus()).isEqualTo(AppointmentStatus.PENDING);
			assertThat(gravado.getGuestName()).isEqualTo("Maria Aparecida");
			assertThat(gravado.getGuestPhone()).isEqualTo("5522999990000");
			// Preco somado do catalogo, nunca do cliente.
			assertThat(gravado.getAmountCents()).isEqualTo(10000);
			// Duracao bruta 60, capada em 50 para ocupar a agenda.
			assertThat(gravado.getSlotDurationMinutes()).isEqualTo(50);

			// No banco a PII esta cifrada.
			assertThat(jdbc.queryForObject(
					"SELECT \"guest_name_encrypted\" FROM \"appointments\" WHERE \"id\" = ?",
					String.class, id)).matches("^[0-9a-f]{32}:[0-9a-f]+$");

			// A resposta devolve a soma BRUTA, sem o teto -- como o Node.
			assertThat(criado.get("data").get("totalDurationMinutes").asInt()).isEqualTo(60);
			assertThat(criado.get("data").get("totalAmountCents").asInt()).isEqualTo(10000);
			assertThat(criado.get("data").get("cancelToken").asString()).isNotBlank();
		}

		/**
		 * Os campos que {@code montar} grava e ninguem conferia.
		 *
		 * <p>Saem da medicao por mutante: remover {@code setNotes},
		 * {@code setGuestEmail} ou o {@code setAmountCents} de cada item
		 * passava pela suite inteira sem quebrar nada.
		 *
		 * <p>O terceiro e o que pesa. Nao e o total do agendamento — esse ja
		 * tinha asercao logo acima — e sim o <b>preco congelado de cada
		 * item</b>, em {@code appointment_services}. Ele e o registro do que
		 * foi contratado, e tem duas saidas visiveis: o
		 * {@code CancelService} soma os itens para montar o total da mensagem
		 * de cancelamento no WhatsApp, e o {@code services[]} da resposta de
		 * criacao vem deles. Com o item zerado, o cliente leria
		 * "Total: R$ 0,00" no cancelamento.
		 *
		 * <p>Os dois servicos tem precos <b>diferentes</b> de proposito: com
		 * valores iguais, trocar um item pelo outro passaria despercebido.
		 */
		@Test
		@DisplayName("notes, e-mail e o preco congelado de cada item vao para o banco")
		void camposOpcionaisEPrecoDeCadaItem() throws Exception {
			com.cauacosenza.barbearia.domain.entity.Service pigmentacao =
					new com.cauacosenza.barbearia.domain.entity.Service();
			pigmentacao.setName("Pigmentação");
			pigmentacao.setAmountCents(7500);
			pigmentacao.setDurationMinutes(20);
			pigmentacao.setActive(true);
			services.saveAndFlush(pigmentacao);

			JsonNode criado = corpoDe(mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "%s",
					  "serviceIds": ["%s", "%s"],
					  "scheduledAt": "2026-12-15T10:20:00-03:00",
					  "guestName": "Maria Aparecida",
					  "guestPhone": "5522999990000",
					  "guestEmail": "maria@exemplo.com.br",
					  "notes": "Prefere máquina 2 nas laterais"
					}
					""".formatted(jhonatan.getId(), corteId, pigmentacao.getId()))));

			String id = criado.get("data").get("id").asString();
			Appointment gravado = appointments.findById(id).orElseThrow();

			assertThat(gravado.getNotes()).isEqualTo("Prefere máquina 2 nas laterais");
			assertThat(gravado.getGuestEmail()).isEqualTo("maria@exemplo.com.br");

			// O e-mail e PII e vai cifrado; a observacao e recado para o
			// barbeiro e fica em claro. A diferenca e deliberada, entao vale
			// afirmar as duas.
			assertThat(jdbc.queryForObject(
					"SELECT \"guest_email_encrypted\" FROM \"appointments\" WHERE \"id\" = ?",
					String.class, id)).matches("^[0-9a-f]{32}:[0-9a-f]+$");
			assertThat(jdbc.queryForObject(
					"SELECT \"notes\" FROM \"appointments\" WHERE \"id\" = ?",
					String.class, id)).isEqualTo("Prefere máquina 2 nas laterais");

			// O preco de cada item, amarrado ao servico que o gerou. Lido da
			// tabela e nao de gravado.getBookedServices(): a colecao e lazy e
			// aqui nao ha transacao aberta. Ler o que esta gravado tambem e
			// mais honesto -- e esta linha que o CancelService vai somar
			// daqui a meses.
			assertThat(jdbc.queryForList("""
					SELECT s."name" AS nome, i."amount_cents" AS valor
					  FROM "appointment_services" i
					  JOIN "services" s ON s."id" = i."service_id"
					 WHERE i."appointment_id" = ?
					""", id))
					.extracting(linha -> linha.get("nome"), linha -> linha.get("valor"))
					.containsExactlyInAnyOrder(
							tuple("Corte", 5000),
							tuple("Pigmentação", 7500));

			// E o total do agendamento e a soma deles, lida do catalogo.
			assertThat(gravado.getAmountCents()).isEqualTo(12500);
			assertThat(criado.get("data").get("totalAmountCents").asInt()).isEqualTo(12500);
		}

		@Test
		@DisplayName("dois servicos somam 60 e ocupam 50: o slot seguinte continua disponivel")
		void tetoDeCinquentaVisivelNaAgenda() throws Exception {
			agendar(TERCA, "10:20", List.of(corteId, barbaId));

			// 10:20 + 50 = 11:10. Sem o teto o bloco iria ate 11:20 e o vao
			// seguinte comecaria mais tarde -- o bug real das 10:00.
			assertThat(consultarHorarios(TERCA, corteId, 50)).contains("11:10");
		}

	}

	// =======================================================================
	// As validacoes de criacao
	// =======================================================================

	@Nested
	@DisplayName("o backend nao confia no frontend")
	class Validacoes {

		@Test
		@DisplayName("dia sem jornada (sexta, ordem de chegada) -> 400")
		void diaSemJornada() throws Exception {
			// A auditoria do Node achou exatamente isto: a tela nunca oferece
			// sexta, mas a API aceitava.
			agendar(SEXTA, "10:00", List.of(corteId))
					.andExpect(status(400))
					.andExpect(mensagem("Profissional não atende neste dia"));

			assertThat(appointments.count()).isZero();
		}

		@Test
		@DisplayName("horario antes da abertura -> 400")
		void antesDaAbertura() throws Exception {
			agendar(TERCA, "07:00", List.of(corteId))
					.andExpect(status(400))
					.andExpect(mensagem("Horário fora do expediente"));
		}

		@Test
		@DisplayName("horario depois do fechamento -> 400")
		void depoisDoFechamento() throws Exception {
			agendar(TERCA, "19:30", List.of(corteId))
					.andExpect(status(400))
					.andExpect(mensagem("Horário fora do expediente"));
		}

		@Test
		@DisplayName("19:20 com 50min ultrapassa o fechamento -> 400 (correcao do Node)")
		void naoCabeNoExpediente() throws Exception {
			// O Node aceitava: 19:20 < 19:30, entao passava, e o atendimento
			// terminava 20:10. Aqui a escrita exige o mesmo que a leitura.
			agendar(TERCA, "19:20", List.of(corteId))
					.andExpect(status(400))
					.andExpect(mensagemContendo("terminaria às 20:10"))
					.andExpect(mensagemContendo("19:30"));

			assertThat(appointments.count()).isZero();
			// E confirma que a leitura tambem nunca ofereceu esse horario.
			assertThat(consultarHorarios(TERCA, corteId, 50)).doesNotContain("19:20");
		}

		@Test
		@DisplayName("um servico curto AINDA cabe as 19:20 — a regra e sobre a duracao, nao a hora")
		void curtoCabeNoFimDoDia() throws Exception {
			// Barba de 10 min: 19:20 + 10 = 19:30, encosta no fechamento.
			agendar(TERCA, "19:20", List.of(barbaId))
					.andExpect(status(201));
		}

		@Test
		@DisplayName("agendar no passado -> 422 pela validacao de entrada")
		void noPassado() throws Exception {
			// O relogio esta em 2026-12-15 03:00 BRT.
			agendar(LocalDate.of(2020, 1, 6), "10:00", List.of(corteId))
					.andExpect(status(422))
					.andExpect(mensagemContendo("Data deve ser no futuro"));
		}

		@Test
		@DisplayName("sobre bloqueio do profissional -> 409")
		void sobreBloqueio() throws Exception {
			ScheduleBlock almoco = new ScheduleBlock();
			almoco.setProfessional(jhonatan);
			almoco.setDate("2026-12-15");
			almoco.setStartTime("12:00");
			almoco.setEndTime("13:00");
			almoco.setReason("Almoço");
			blocks.saveAndFlush(almoco);

			agendar(TERCA, "12:00", List.of(corteId))
					.andExpect(status(409))
					.andExpect(mensagem("Este horário está reservado pelo profissional"));

			// E a leitura concorda: o horario nem aparece.
			assertThat(consultarHorarios(TERCA, corteId, 50)).doesNotContain("12:00");
		}

		@Test
		@DisplayName("encostar no bloqueio nao colide")
		void encostadoNoBloqueioPassa() throws Exception {
			ScheduleBlock almoco = new ScheduleBlock();
			almoco.setProfessional(jhonatan);
			almoco.setDate("2026-12-15");
			almoco.setStartTime("12:00");
			almoco.setEndTime("13:00");
			blocks.saveAndFlush(almoco);

			// 13:00 + 10min de barba termina 13:10, depois do bloqueio.
			agendar(TERCA, "13:00", List.of(barbaId)).andExpect(status(201));
		}

		/*
		 * Os quatro casos abaixo saem da medicao por mutante: a aritmetica de
		 * validarBloqueios tinha cinco mutantes sobrevivendo a suite inteira.
		 * O motivo era estreito e concreto -- o unico bloqueio testado ia de
		 * 12:00 a 13:00, com minuto zero nas duas pontas, e so a borda "o
		 * slot comeca exatamente quando o bloqueio termina" tinha teste.
		 *
		 * Por isso todos usam minutos diferentes de zero, e cada um escolhe
		 * os horarios para que uma alteracao especifica da aritmetica mude a
		 * resposta. Nao sao quatro variacoes do mesmo caso: sao quatro
		 * posicoes distintas do slot em relacao ao bloqueio.
		 */

		@Test
		@DisplayName("o slot termina exatamente quando o bloqueio comeca -> passa")
		void terminarNoInicioDoBloqueioPassa() throws Exception {
			// A borda espelhada da de cima, e a que faltava. Barba de 10min
			// as 13:00 termina 13:10, que e quando o bloqueio comeca:
			// encostar nao colide, dos dois lados.
			bloquear("13:10", "13:50");

			agendar(TERCA, "13:00", List.of(barbaId)).andExpect(status(201));
		}

		@Test
		@DisplayName("bloqueio inteiro antes do slot -> passa")
		void bloqueioAntesDoSlotPassa() throws Exception {
			// 09:10-09:50 termina muito antes de 10:20-11:10. O minuto do
			// INICIO DO SLOT e o que importa aqui: somar ou subtrair os 20
			// minutos das 10:20 decide se o slot parece comecar antes do fim
			// do bloqueio.
			bloquear("09:10", "09:50");

			agendar(TERCA, "10:20", List.of(corteId)).andExpect(status(201));
		}

		@Test
		@DisplayName("bloqueio inteiro depois do slot, por 10 minutos -> passa")
		void bloqueioDepoisDoSlotPassa() throws Exception {
			// 13:10-14:00 termina 10 minutos antes de 14:10. A folga e
			// pequena de proposito: e o minuto do INICIO DO BLOQUEIO que
			// decide, e com 14:10 lido como 14:00 o slot passaria a colidir.
			bloquear("14:10", "14:50");

			agendar(TERCA, "13:10", List.of(corteId)).andExpect(status(201));
		}

		@Test
		@DisplayName("slot comeca dentro do bloqueio, com minutos quebrados -> 409")
		void comecarDentroDoBloqueioColide() throws Exception {
			// 14:20 cai dentro de 14:10-14:50. Aqui quem decide e o minuto do
			// FIM DO BLOQUEIO: com 14:50 lido como 14:40, o slot das 14:20
			// deixaria de colidir e o cliente reservaria em cima do bloqueio.
			bloquear("14:10", "14:50");

			agendar(TERCA, "14:20", List.of(corteId))
					.andExpect(status(409))
					.andExpect(mensagem("Este horário está reservado pelo profissional"));
		}

		@Test
		@DisplayName("servico inexistente -> 404")
		void servicoInexistente() throws Exception {
			agendar(TERCA, "10:20", List.of("99999999-9999-4999-8999-999999999999"))
					.andExpect(status(404))
					.andExpect(mensagemContendo("Service not found"));
		}

		@Test
		@DisplayName("servico desativado e indistinguivel de inexistente -> 404")
		void servicoDesativado() throws Exception {
			com.cauacosenza.barbearia.domain.entity.Service desativado = novoServico("Antigo", 30);
			desativado.setActive(false);
			services.saveAndFlush(desativado);

			agendar(TERCA, "10:20", List.of(desativado.getId()))
					.andExpect(status(404));
		}

		@Test
		@DisplayName("profissional inativo -> 404")
		void profissionalInativo() throws Exception {
			jhonatan.setActive(false);
			barbers.saveAndFlush(jhonatan);

			agendar(TERCA, "10:20", List.of(corteId))
					.andExpect(status(404))
					.andExpect(mensagem("Professional not found"));
		}

		@Test
		@DisplayName("o mesmo horario duas vezes -> 409 com mensagem de negocio")
		void horarioJaOcupado() throws Exception {
			agendar(TERCA, "10:20", List.of(corteId)).andExpect(status(201));

			agendar(TERCA, "10:20", List.of(corteId))
					.andExpect(status(409))
					.andExpect(mensagem("Este horário acabou de ser reservado. Escolha outro."));

			assertThat(appointments.count()).isEqualTo(1);
		}

	}

	// =======================================================================
	// Antecedencia minima
	// =======================================================================

	@Nested
	@DisplayName("antecedencia minima de 15 minutos no proprio dia")
	class Antecedencia {

		@Test
		@DisplayName("horarios a menos de 15 minutos de agora somem da lista de hoje")
		void cortaOsProximos() throws Exception {
			// Relogio em 2026-12-15 03:00 BRT: tudo ainda esta longe.
			assertThat(consultarHorarios(TERCA, corteId, 50)).contains("08:40");

			// Avanca o relogio para 08:30 BRT: 08:40 esta a 10 minutos.
			org.mockito.BDDMockito.given(clock.instant())
					.willReturn(Instant.parse("2026-12-15T11:30:00Z"));

			assertThat(consultarHorarios(TERCA, corteId, 50)).doesNotContain("08:40");
		}

		@Test
		@DisplayName("em dia futuro nada e cortado por antecedencia")
		void diaFuturoNaoCorta() throws Exception {
			org.mockito.BDDMockito.given(clock.instant())
					.willReturn(Instant.parse("2026-12-15T22:00:00Z"));

			// 2026-12-16 e quarta, tambem com jornada. Mesmo com o relogio as
			// 19:00 de hoje, amanha comeca as 08:40.
			assertThat(consultarHorarios(LocalDate.of(2026, 12, 16), corteId, 50))
					.contains("08:40");
		}

	}

	// =======================================================================
	// Cancelamento
	// =======================================================================

	@Nested
	@DisplayName("cancelamento pelo link")
	class Cancelamento {

		@Test
		@DisplayName("com mais de uma hora de antecedencia: canCancel true e o POST funciona")
		void cancelaComAntecedencia() throws Exception {
			String token = corpoDe(agendar(TERCA, "14:00", List.of(corteId)))
					.get("data").get("cancelToken").asString();

			// Relogio em 03:00 BRT, atendimento as 14:00: onze horas.
			JsonNode tela = corpoDe(mvc.perform(getComIpProprio("/api/v1/cancel/" + token)));
			assertThat(tela.get("data").get("canCancel").asBoolean()).isTrue();
			assertThat(tela.get("data").get("guestName").asString()).isEqualTo("Maria Aparecida");

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(200));

			// Pelo ID, e nao pelo token: o cancelamento APAGA o cancel_token,
			// para que o link morra junto. Ver TokenDeCancelamentoTest.
			Appointment cancelado = appointments
					.findById(tela.get("data").get("id").asString()).orElseThrow();
			assertThat(cancelado.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
			assertThat(cancelado.getCancelledAt()).isNotNull();
			assertThat(cancelado.getCancelToken()).isNull();
		}

		@Test
		@DisplayName("o horario cancelado volta a ser vendavel")
		void horarioVoltaAListaAposCancelar() throws Exception {
			// 14:30 e nao 14:00: com agenda vazia a grade e 08:40, 09:30,
			// 10:20 ... de 50 em 50, e 14:00 nunca esteve nela. Afirmar que um
			// horario "voltou" a lista exige escolher um que a lista oferece --
			// senao o teste passa na primeira asercao por vacuidade e falha na
			// segunda sem que nada esteja errado no sistema.
			String token = corpoDe(agendar(TERCA, "14:30", List.of(corteId)))
					.get("data").get("cancelToken").asString();

			assertThat(consultarHorarios(TERCA, corteId, 50)).doesNotContain("14:30");

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(200));

			// Sai do WHERE parcial da constraint e do filtro da consulta de uma
			// vez so. Nao ha nada a limpar.
			assertThat(consultarHorarios(TERCA, corteId, 50)).contains("14:30");
			agendar(TERCA, "14:30", List.of(corteId)).andExpect(status(201));
		}

		@Test
		@DisplayName("com 30 minutos de antecedencia: canCancel false e o POST recusa com 400")
		void prazoEncerrado() throws Exception {
			String token = corpoDe(agendar(TERCA, "14:00", List.of(corteId)))
					.get("data").get("cancelToken").asString();

			// Relogio para 13:30 BRT: faltam 30 minutos.
			org.mockito.BDDMockito.given(clock.instant())
					.willReturn(Instant.parse("2026-12-15T16:30:00Z"));

			JsonNode tela = corpoDe(mvc.perform(getComIpProprio("/api/v1/cancel/" + token)));
			// A tela e o POST tem que concordar, ou o botao aparece habilitado
			// e o clique falha.
			assertThat(tela.get("data").get("canCancel").asBoolean()).isFalse();

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}"))
					.andExpect(status(400))
					.andExpect(mensagem(
							"Prazo de cancelamento encerrado. Entre em contato com a barbearia."));

			assertThat(appointments.findByCancelToken(token).orElseThrow().getStatus())
					.isEqualTo(AppointmentStatus.PENDING);
		}

		@Test
		@DisplayName("faltando exatamente uma hora o prazo ja venceu")
		void bordaDaUmaHora() throws Exception {
			String token = corpoDe(agendar(TERCA, "14:00", List.of(corteId)))
					.get("data").get("cancelToken").asString();

			// 13:00 BRT em ponto: exatamente uma hora.
			org.mockito.BDDMockito.given(clock.instant())
					.willReturn(Instant.parse("2026-12-15T16:00:00Z"));

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(400));

			// Um segundo antes ainda passa.
			org.mockito.BDDMockito.given(clock.instant())
					.willReturn(Instant.parse("2026-12-15T15:59:59Z"));
			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(200));
		}

		@Test
		@DisplayName("cancelar duas vezes: a segunda e 404 e preserva o cancelled_at")
		void segundoCancelamentoEhIdempotente() throws Exception {
			JsonNode criado = corpoDe(agendar(TERCA, "14:00", List.of(corteId)));
			String token = criado.get("data").get("cancelToken").asString();
			String id = criado.get("data").get("id").asString();

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(200));
			Instant primeiroCancelamento =
					appointments.findById(id).orElseThrow().getCancelledAt();

			// O relogio anda entre os dois cliques.
			org.mockito.BDDMockito.given(clock.instant())
					.willReturn(Instant.parse("2026-12-15T12:00:00Z"));

			// 404, e nao mais 400: o primeiro clique apagou o token, entao a
			// busca falha antes de qualquer regra de negocio. E deliberado --
			// token morto responde igual a token inexistente, para nao haver
			// canal de distincao.
			mvc.perform(postJson("/api/v1/cancel/" + token, "{}"))
					.andExpect(status(404))
					.andExpect(mensagem("Agendamento não encontrado"));

			Appointment depois = appointments.findById(id).orElseThrow();
			assertThat(depois.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
			// A hora real do cancelamento nao foi sobrescrita pela hora de
			// alguem conferindo o link.
			assertThat(depois.getCancelledAt()).isEqualTo(primeiroCancelamento);
		}

		@Test
		@DisplayName("token inexistente -> 404")
		void tokenInexistente() throws Exception {
			mvc.perform(getComIpProprio("/api/v1/cancel/99999999-9999-4999-8999-999999999999"))
					.andExpect(status(404))
					.andExpect(mensagem("Agendamento não encontrado"));
		}

	}

	// =======================================================================
	// Catalogo
	// =======================================================================

	@Nested
	@DisplayName("catalogo")
	class Catalogo {

		@Test
		@DisplayName("services devolve os ativos com amountCents, na ordem de cadastro")
		void servicos() throws Exception {
			JsonNode corpo = corpoDe(mvc.perform(getComIpProprio("/api/v1/services")));
			JsonNode lista = corpo.get("data");

			assertThat(lista.size()).isEqualTo(2);
			assertThat(lista.get(0).get("name").asString()).isEqualTo("Corte");
			assertThat(lista.get(1).get("name").asString()).isEqualTo("Barba");
			// O campo de que a home depende para mostrar preco.
			assertThat(lista.get(0).has("amountCents")).isTrue();
		}

		@Test
		@DisplayName("professionals devolve nome decifrado e a jornada ordenada")
		void profissionais() throws Exception {
			JsonNode corpo = corpoDe(mvc.perform(getComIpProprio("/api/v1/professionals")));
			JsonNode profissional = corpo.get("data").get(0);

			assertThat(profissional.get("name").asString()).isEqualTo("Jhonatan Correa");
			assertThat(profissional.get("isWalkIn").asBoolean()).isFalse();
			assertThat(profissional.get("availability").size()).isEqualTo(4);
			assertThat(profissional.get("availability").get(0).get("dayOfWeek").asInt()).isEqualTo(1);
			assertThat(profissional.get("availability").get(0).get("startTime").asString())
					.isEqualTo("08:40");
		}

		@Test
		@DisplayName("profissional inativo some da lista")
		void inativoSome() throws Exception {
			jhonatan.setActive(false);
			barbers.saveAndFlush(jhonatan);

			JsonNode corpo = corpoDe(mvc.perform(getComIpProprio("/api/v1/professionals")));
			assertThat(corpo.get("data")).isEmpty();
		}

		@Test
		@DisplayName("dia sem jornada devolve lista vazia de horarios, nao erro")
		void sextaDevolveVazio() throws Exception {
			assertThat(consultarHorarios(SEXTA, corteId, 50)).isEmpty();
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	/** Dispara a criacao com um corpo valido no horario e servicos dados. */
	private org.springframework.test.web.servlet.ResultActions agendar(
			LocalDate dia, String hora, List<String> servicoIds) throws Exception {

		String ids = servicoIds.stream()
				.map(id -> "\"" + id + "\"")
				.collect(java.util.stream.Collectors.joining(","));

		return mvc.perform(postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": [%s],
				  "scheduledAt": "%sT%s:00-03:00",
				  "guestName": "Maria Aparecida",
				  "guestPhone": "5522999990000"
				}
				""".formatted(jhonatan.getId(), ids, dia, hora)));
	}

	/** Um bloqueio do profissional na {@link #TERCA}, no intervalo dado. */
	private void bloquear(String inicio, String fim) {
		ScheduleBlock bloqueio = new ScheduleBlock();
		bloqueio.setProfessional(this.jhonatan);
		bloqueio.setDate(TERCA.toString());
		bloqueio.setStartTime(inicio);
		bloqueio.setEndTime(fim);
		this.blocks.saveAndFlush(bloqueio);
	}

	private List<String> consultarHorarios(LocalDate dia, String servicoId, int duracao)
			throws Exception {

		JsonNode corpo = corpoDe(mvc.perform(postJson("/api/v1/available-times", """
				{
				  "professionalId": "%s",
				  "serviceId": "%s",
				  "date": "%s",
				  "totalDurationMinutes": %d
				}
				""".formatted(jhonatan.getId(), servicoId, dia, duracao))));

		// A asercao de sucesso fica aqui e nao em cada teste: sem ela, uma
		// consulta que falhou vira NullPointerException em "data.slots" e a
		// causa real (o status e a mensagem) se perde.
		assertThat(corpo.get("success").asBoolean())
				.as("consulta de horarios falhou: %s", corpo)
				.isTrue();

		return corpo.get("data").get("slots").valueStream()
				.map(JsonNode::asString)
				.toList();
	}

	private JsonNode corpoDe(org.springframework.test.web.servlet.ResultActions resultado)
			throws Exception {

		return this.json.readTree(
				resultado.andReturn().getResponse().getContentAsString(
						java.nio.charset.StandardCharsets.UTF_8));
	}

	private static org.springframework.test.web.servlet.ResultMatcher status(int esperado) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(esperado);
	}

	private static org.springframework.test.web.servlet.ResultMatcher mensagem(String esperada) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers
				.jsonPath("$.message").value(esperada);
	}

	private static org.springframework.test.web.servlet.ResultMatcher mensagemContendo(String trecho) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers
				.jsonPath("$.message").value(org.hamcrest.Matchers.containsString(trecho));
	}

}
