package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.cauacosenza.barbearia.domain.entity.AdminUser;
import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.repository.AdminUserRepository;
import com.cauacosenza.barbearia.security.CookieFactory;

import tools.jackson.databind.JsonNode;

/**
 * O painel do dono: contadores, agenda do dia, mudanca de status e bloqueios.
 *
 * <p>O relogio e fixo em <b>2026-12-15, uma terca-feira</b>, as 10:00 de
 * Brasilia. Terca e o primeiro dia da semana de trabalho, o que torna o teste
 * de semana legivel: tudo que cai entre 15 e 19 de dezembro pertence a semana
 * corrente.
 */
class AdminPanelIntegrationTest extends AbstractPostgresIntegrationTest {

	/** Terca-feira. Primeiro dia da semana de trabalho. */
	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	/** 2026-12-15 10:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T13:00:00Z");

	private static final String EMAIL = "admin@barbearia.com";

	private static final String SENHA = "Admin@123456";

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdminUserRepository usuarios;

	@Autowired
	private PasswordEncoder senhas;

	private Barber jhonatan;

	private Service corte;

	private Service barba;

	private jakarta.servlet.http.Cookie sessao;

	@BeforeEach
	void prepararPainel() throws Exception {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);

		AdminUser admin = new AdminUser();
		admin.setEmail(EMAIL);
		admin.setPasswordHash(this.senhas.encode(SENHA));
		admin.setName("Jhonatan Correa");
		admin.setPhone("5522999990000");
		admin.setRole(Role.ADMIN);
		this.usuarios.saveAndFlush(admin);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corte = novoServico("Corte Disfarçado", 50);
		this.barba = novoServico("Pigmentação de Barba", 25);

		BarberSchedule terca = new BarberSchedule();
		terca.setProfessional(this.jhonatan);
		terca.setDayOfWeek(2);
		terca.setStartTime("08:40");
		terca.setEndTime("19:30");
		this.schedules.saveAndFlush(terca);

		this.sessao = logar();
	}

	// =======================================================================
	// Listagem
	// =======================================================================

	@Nested
	@DisplayName("GET /api/v1/admin/appointments")
	class Listagem {

		@Test
		@DisplayName("traz TODOS os servicos do agendamento, nao so o principal")
		void todosOsServicos() throws Exception {
			// O bug real: o painel mostrava "Corte Disfarcado" e a Pigmentacao
			// sumia. O barbeiro so descobria o servico a mais na cadeira.
			agendar(LocalTime.of(10, 20), List.of(corte, barba));

			JsonNode linha = agenda(TERCA).get(0);

			assertThat(linha.get("services").size()).isEqualTo(2);
			// Ordem deterministica: o servico mais longo primeiro. Sem
			// ordenar, a lista sairia na ordem do plano de execucao e o painel
			// mostraria "Pigmentação + Corte" numa recarga e o inverso na
			// seguinte. Ver AdminAppointmentResponse.ORDEM_DE_EXIBICAO.
			assertThat(linha.get("services").valueStream()
					.map(s -> s.get("name").asString()).toList())
					.containsExactly("Corte Disfarçado", "Pigmentação de Barba");
			// O painel soma services[].durationMinutes para mostrar a duracao.
			assertThat(linha.get("services").valueStream()
					.map(s -> s.get("durationMinutes").asInt()).toList())
					.containsExactly(50, 25);

			// E o servico principal da coluna legada continua vindo, porque o
			// painel o usa como fallback quando services esta vazio.
			assertThat(linha.get("service").get("name").asString()).isEqualTo("Corte Disfarçado");
		}

		@Test
		@DisplayName("a PII chega decifrada ao painel")
		void piiDecifrada() throws Exception {
			agendar(LocalTime.of(10, 20), List.of(corte));

			JsonNode linha = agenda(TERCA).get(0);

			assertThat(linha.get("customerName").asString()).isEqualTo("Maria Aparecida");
			assertThat(linha.get("customerPhone").asString()).isEqualTo("5522999990000");
			// O profissional tambem: name_encrypted decifrado.
			assertThat(linha.get("professional").get("name").asString()).isEqualTo("Jhonatan Correa");
		}

		@Test
		@DisplayName("mostra TODOS os status, inclusive cancelado — e o historico do dia")
		void todosOsStatus() throws Exception {
			// Contraste com a consulta que alimenta o calculo de horarios, que
			// so traz o que ocupa agenda.
			for (AppointmentStatus status : AppointmentStatus.values()) {
				Appointment agendamento = novoAgendamento(jhonatan, corte, TERCA,
						LocalTime.of(9 + status.ordinal(), 0), status);
				agendamento.setGuestName("Cliente " + status);
				agendamento.setGuestPhone("5522999990000");
				gravar(agendamento);
			}

			assertThat(agenda(TERCA).size()).isEqualTo(AppointmentStatus.values().length);
		}

		@Test
		@DisplayName("vem ordenada por horario")
		void ordenada() throws Exception {
			// Todos depois das 10:00 do relogio fixo: criar agendamento no
			// passado e recusado, e o teste aqui e de ordenacao.
			agendar(LocalTime.of(15, 20), List.of(corte));
			agendar(LocalTime.of(10, 20), List.of(barba));
			agendar(LocalTime.of(12, 0), List.of(barba));

			List<String> horas = agenda(TERCA).valueStream()
					.map(linha -> mapperDeTeste().toHoraTexto(
							Instant.parse(linha.get("scheduledAt").asString())
									.atZone(com.cauacosenza.barbearia.service.SlotQueryMapper.FUSO_BRASILIA)
									.toLocalTime()))
					.toList();

			assertThat(horas).containsExactly("10:20", "12:00", "15:20");
		}

		@Test
		@DisplayName("o dia e o de Brasilia: 23:50 BRT conta no dia certo, nao no seguinte")
		void viradaDoDia() throws Exception {
			// 23:50 BRT de 15/12 e 02:50 UTC de 16/12. Com recorte em UTC o
			// agendamento cairia no dia seguinte e sumiria do painel do dia.
			Appointment tarde = novoAgendamentoUtc(jhonatan, corte,
					mapperDeTeste().toInstantUtc(TERCA, LocalTime.of(23, 50)), 50,
					AppointmentStatus.CONFIRMED);
			tarde.setGuestName("Cliente da Madrugada");
			tarde.setGuestPhone("5522999990000");
			gravar(tarde);

			assertThat(agenda(TERCA).size()).isEqualTo(1);
			assertThat(agenda(TERCA.plusDays(1)).size()).isZero();
		}

		@Test
		@DisplayName("00:00 BRT conta no dia que comeca, nao no anterior")
		void bordaDaMeiaNoite() throws Exception {
			Appointment meiaNoite = novoAgendamentoUtc(jhonatan, corte,
					mapperDeTeste().toInstantUtc(TERCA, LocalTime.MIDNIGHT), 50,
					AppointmentStatus.CONFIRMED);
			meiaNoite.setGuestName("Cliente");
			meiaNoite.setGuestPhone("5522999990000");
			gravar(meiaNoite);

			assertThat(agenda(TERCA).size()).isEqualTo(1);
			assertThat(agenda(TERCA.minusDays(1)).size()).isZero();
		}

		@Test
		@DisplayName("sem date, usa hoje em Brasilia")
		void semDate() throws Exception {
			agendar(LocalTime.of(10, 20), List.of(corte));

			JsonNode corpo = corpoDe(mvc.perform(
					getComIpProprio("/api/v1/admin/appointments").cookie(sessao))
					.andExpect(status(200)).andReturn());

			assertThat(corpo.get("data").size()).isEqualTo(1);
		}

		@Test
		@DisplayName("date malformada devolve 422")
		void dateMalformada() throws Exception {
			mvc.perform(getComIpProprio("/api/v1/admin/appointments?date=15-12-2026").cookie(sessao))
					.andExpect(status(422));
		}

	}

	// =======================================================================
	// Stats
	// =======================================================================

	@Nested
	@DisplayName("GET /api/v1/admin/stats")
	class Estatisticas {

		@Test
		@DisplayName("a semana vai de TERCA a SABADO")
		void semanaTercaASabado() throws Exception {
			// Relogio na terca 15/12. A semana de trabalho e 15 a 19 (ter-sab).
			// Segunda 14/12 e domingo 20/12 ficam de fora.
			agendarEm(TERCA.minusDays(1), LocalTime.of(10, 0));   // segunda 14 — fora
			agendarEm(TERCA, LocalTime.of(10, 20));               // terca   15 — dentro
			agendarEm(TERCA.plusDays(1), LocalTime.of(10, 0));    // quarta  16 — dentro
			agendarEm(TERCA.plusDays(4), LocalTime.of(10, 0));    // sabado  19 — dentro
			agendarEm(TERCA.plusDays(5), LocalTime.of(10, 0));    // domingo 20 — fora

			JsonNode stats = stats();

			assertThat(stats.get("weekCount").asLong()).isEqualTo(3);
		}

		@ParameterizedTest(name = "consultado em {0}, a semana comeca em {1}")
		@CsvSource({
				// Terca a sabado: a semana corrente.
				"2026-12-15, 2026-12-15",
				"2026-12-16, 2026-12-15",
				"2026-12-19, 2026-12-15",
				// Domingo e segunda apontam para a terca ANTERIOR: nesses dois
				// dias o painel mostra a semana que acabou de terminar. E o
				// comportamento do Node, e faz sentido para quem olha o painel
				// na folga.
				"2026-12-20, 2026-12-15",
				"2026-12-21, 2026-12-15",
				"2026-12-22, 2026-12-22",
		})
		@DisplayName("a terca de referencia")
		void tercaDeReferencia(LocalDate quando, LocalDate inicioEsperado) throws Exception {
			BDDMockito.given(clock.instant())
					.willReturn(mapperDeTeste().toInstantUtc(quando, LocalTime.of(10, 0)));
			// O access token foi emitido com o relogio antigo e agora esta
			// "expirado": uma sessao nova e obrigatoria depois de mexer no
			// tempo.
			sessao = logar();

			// Um agendamento no primeiro dia da semana esperada e outro no dia
			// anterior a ela.
			agendarEm(inicioEsperado, LocalTime.of(10, 20));
			agendarEm(inicioEsperado.minusDays(1), LocalTime.of(10, 20));

			assertThat(stats().get("weekCount").asLong())
					.as("so o do inicio da semana conta")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("cancelado e no-show nao contam como atendimento")
		void canceladoNaoConta() throws Exception {
			agendarComStatus(TERCA, LocalTime.of(9, 0), AppointmentStatus.PENDING);
			agendarComStatus(TERCA, LocalTime.of(10, 0), AppointmentStatus.CONFIRMED);
			agendarComStatus(TERCA, LocalTime.of(11, 0), AppointmentStatus.COMPLETED);
			agendarComStatus(TERCA, LocalTime.of(12, 0), AppointmentStatus.CANCELLED);
			agendarComStatus(TERCA, LocalTime.of(13, 0), AppointmentStatus.NO_SHOW);

			JsonNode stats = stats();

			// PENDING + CONFIRMED + COMPLETED.
			assertThat(stats.get("todayCount").asLong()).isEqualTo(3);
			assertThat(stats.get("weekCount").asLong()).isEqualTo(3);
			assertThat(stats.get("monthCount").asLong()).isEqualTo(3);
		}

		@Test
		@DisplayName("o contador de hoje respeita a virada do dia em Brasilia")
		void hojeNaViradaDoDia() throws Exception {
			// 23:50 BRT de hoje: em UTC ja e amanha.
			agendarComStatus(TERCA, LocalTime.of(23, 50), AppointmentStatus.CONFIRMED);
			// 00:30 BRT de amanha. Nao 00:10: o de 23:50 ocupa 30 minutos e
			// iria ate 00:20, e a constraint de exclusao da V2 recusaria a
			// sobreposicao -- corretamente.
			agendarComStatus(TERCA.plusDays(1), LocalTime.of(0, 30), AppointmentStatus.CONFIRMED);

			assertThat(stats().get("todayCount").asLong()).isEqualTo(1);
		}

		@Test
		@DisplayName("o mes vai do primeiro ao ultimo dia")
		void mesCompleto() throws Exception {
			agendarEm(LocalDate.of(2026, 12, 1), LocalTime.of(10, 0));
			agendarEm(LocalDate.of(2026, 12, 31), LocalTime.of(10, 0));
			// Fora: novembro e janeiro.
			agendarEm(LocalDate.of(2026, 11, 30), LocalTime.of(10, 0));
			agendarEm(LocalDate.of(2027, 1, 1), LocalTime.of(10, 0));

			assertThat(stats().get("monthCount").asLong()).isEqualTo(2);
		}

		@Test
		@DisplayName("noShowCount e ACUMULADO desde sempre — divergencia conhecida do Node")
		void noShowAcumulado() throws Exception {
			// Os outros tres contadores sao por periodo; este nao. Reproduzido
			// como esta, e registrado no README.
			agendarComStatus(TERCA, LocalTime.of(10, 0), AppointmentStatus.NO_SHOW);
			// Um ano antes, muito fora de qualquer recorte.
			agendarComStatus(LocalDate.of(2025, 3, 10), LocalTime.of(10, 0), AppointmentStatus.NO_SHOW);
			// E um no futuro.
			agendarComStatus(LocalDate.of(2027, 6, 10), LocalTime.of(10, 0), AppointmentStatus.NO_SHOW);

			assertThat(stats().get("noShowCount").asLong()).isEqualTo(3);
			// Enquanto o de hoje ignora os no-shows por completo.
			assertThat(stats().get("todayCount").asLong()).isZero();
		}

	}

	// =======================================================================
	// Mudanca de status
	// =======================================================================

	@Nested
	@DisplayName("PUT /api/v1/admin/appointments/{id}/status")
	class MudancaDeStatus {

		@Test
		@DisplayName("finalizar muda para COMPLETED")
		void finalizar() throws Exception {
			String id = agendar(LocalTime.of(10, 20), List.of(corte));

			JsonNode corpo = corpoDe(mudarStatus(id, "COMPLETED").andExpect(status(200)).andReturn());

			assertThat(corpo.get("data").get("id").asString()).isEqualTo(id);
			assertThat(corpo.get("data").get("status").asString()).isEqualTo("COMPLETED");
			assertThat(corpo.get("message").asString()).isEqualTo("Status atualizado");
			assertThat(appointments.findById(id).orElseThrow().getStatus())
					.isEqualTo(AppointmentStatus.COMPLETED);
		}

		@Test
		@DisplayName("cancelar muda para CANCELLED e LIBERA o horario")
		void cancelarLiberaOHorario() throws Exception {
			String id = agendar(LocalTime.of(10, 20), List.of(corte));

			// Enquanto PENDING, o horario esta ocupado.
			assertThat(horariosLivres()).doesNotContain("10:20");

			mudarStatus(id, "CANCELLED").andExpect(status(200));

			// Sai do WHERE parcial da constraint de exclusao no mesmo UPDATE.
			assertThat(horariosLivres()).contains("10:20");
			// E da para reservar de novo.
			assertThat(reservar(LocalTime.of(10, 20))).isEqualTo(201);
		}

		@Test
		@DisplayName("PENDING e recusado com 422 — o admin nao volta ao estado inicial")
		void pendingRecusado() throws Exception {
			String id = agendar(LocalTime.of(10, 20), List.of(corte));

			JsonNode corpo = corpoDe(mudarStatus(id, "PENDING").andExpect(status(422)).andReturn());

			assertThat(corpo.get("error").asString()).isEqualTo("Validation Error");
			assertThat(corpo.get("message").asString()).contains("status");
		}

		@Test
		@DisplayName("status inexistente devolve 400, nao 500")
		void statusInexistente() throws Exception {
			String id = agendar(LocalTime.of(10, 20), List.of(corte));

			// Valor fora do enum falha na desserializacao do Jackson.
			mvc.perform(putJson("/api/v1/admin/appointments/" + id + "/status",
					"{\"status\":\"INVENTADO\"}").cookie(sessao))
					.andExpect(status(400));
		}

		@Test
		@DisplayName("agendamento inexistente devolve 404")
		void inexistente() throws Exception {
			mudarStatus("99999999-9999-4999-8999-999999999999", "COMPLETED")
					.andExpect(status(404));
		}

		@Test
		@DisplayName("sem sessao devolve 401")
		void semSessao() throws Exception {
			String id = agendar(LocalTime.of(10, 20), List.of(corte));

			mvc.perform(putJson("/api/v1/admin/appointments/" + id + "/status",
					"{\"status\":\"COMPLETED\"}"))
					.andExpect(status(401));
		}

	}

	// =======================================================================
	// Blocked slots
	// =======================================================================

	@Nested
	@DisplayName("/api/v1/admin/blocked-slots")
	class Bloqueios {

		@Test
		@DisplayName("criar devolve 201 com a linha inteira")
		void criar() throws Exception {
			JsonNode corpo = corpoDe(criarBloqueio(TERCA, "12:00", "13:00", "Almoço")
					.andExpect(status(201)).andReturn());

			JsonNode bloqueio = corpo.get("data");
			assertThat(corpo.get("message").asString()).isEqualTo("Slot blocked");
			assertThat(bloqueio.get("date").asString()).isEqualTo("2026-12-15");
			assertThat(bloqueio.get("startTime").asString()).isEqualTo("12:00");
			assertThat(bloqueio.get("endTime").asString()).isEqualTo("13:00");
			assertThat(bloqueio.get("reason").asString()).isEqualTo("Almoço");
			assertThat(bloqueio.get("professionalId").asString()).isEqualTo(jhonatan.getId());
			assertThat(bloqueio.get("id").asString()).isNotBlank();
			assertThat(bloqueio.get("createdAt").asString()).isNotBlank();
		}

		@Test
		@DisplayName("o bloqueio tira o horario da lista publica")
		void bloqueioRemoveHorario() throws Exception {
			assertThat(horariosLivres()).contains("12:00");

			criarBloqueio(TERCA, "12:00", "13:00", null).andExpect(status(201));

			assertThat(horariosLivres()).doesNotContain("12:00");
		}

		@Test
		@DisplayName("listar devolve os do dia, ordenados por inicio")
		void listar() throws Exception {
			criarBloqueio(TERCA, "15:00", "15:30", null).andExpect(status(201));
			criarBloqueio(TERCA, "12:00", "13:00", null).andExpect(status(201));
			criarBloqueio(TERCA.plusDays(1), "09:00", "10:00", null).andExpect(status(201));

            JsonNode lista = corpoDe(mvc.perform(getComIpProprio(
					"/api/v1/admin/blocked-slots?date=2026-12-15&professionalId=" + jhonatan.getId())
					.cookie(sessao)).andExpect(status(200)).andReturn()).get("data");

			assertThat(lista.size()).isEqualTo(2);
			assertThat(lista.get(0).get("startTime").asString()).isEqualTo("12:00");
			assertThat(lista.get(1).get("startTime").asString()).isEqualTo("15:00");
		}

		@Test
		@DisplayName("remover devolve o horario a venda")
		void remover() throws Exception {
			String id = corpoDe(criarBloqueio(TERCA, "12:00", "13:00", null)
					.andExpect(status(201)).andReturn()).get("data").get("id").asString();

			assertThat(horariosLivres()).doesNotContain("12:00");

			JsonNode corpo = corpoDe(mvc.perform(
					deleteComIpProprio("/api/v1/admin/blocked-slots/" + id).cookie(sessao))
					.andExpect(status(200)).andReturn());

			assertThat(corpo.get("message").asString()).isEqualTo("Blocked slot removed");
			assertThat(horariosLivres()).contains("12:00");
		}

		@Test
		@DisplayName("remover inexistente devolve 404")
		void removerInexistente() throws Exception {
			mvc.perform(deleteComIpProprio(
					"/api/v1/admin/blocked-slots/99999999-9999-4999-8999-999999999999")
					.cookie(sessao))
					.andExpect(status(404));
		}

		@Test
		@DisplayName("data no passado devolve 400, comparada em Brasilia")
		void dataNoPassado() throws Exception {
			JsonNode corpo = corpoDe(criarBloqueio(TERCA.minusDays(1), "12:00", "13:00", null)
					.andExpect(status(400)).andReturn());

			assertThat(corpo.get("message").asString()).isEqualTo("Cannot block slots in the past");
		}

		@Test
		@DisplayName("as 23:50 de Brasilia ainda da para bloquear AMANHA")
		void bloqueioParaAmanhaTardeDaNoite() throws Exception {
			// Em UTC ja seria o dia seguinte, e "amanha" pareceria "hoje" --
			// mas a comparacao e com hoje de Brasilia. Com recorte em UTC,
			// bloquear depois de amanha falharia. Foi um bug real.
			BDDMockito.given(clock.instant())
					.willReturn(mapperDeTeste().toInstantUtc(TERCA, LocalTime.of(23, 50)));
			sessao = logar();

			criarBloqueio(TERCA.plusDays(1), "09:00", "10:00", null).andExpect(status(201));
			// E hoje ainda vale.
			criarBloqueio(TERCA, "09:00", "10:00", null).andExpect(status(201));
		}

		@Test
		@DisplayName("startTime depois de endTime devolve 422")
		void intervaloInvertido() throws Exception {
			JsonNode corpo = corpoDe(criarBloqueio(TERCA, "13:00", "12:00", null)
					.andExpect(status(422)).andReturn());

			assertThat(corpo.get("message").asString())
					.contains("startTime must be earlier than endTime");
		}

		@Test
		@DisplayName("criar sobre horario ja agendado AINDA e permitido — ver README")
		void sobreAgendamentoExistentePassa() throws Exception {
			// O comportamento correto seria 409 com a lista de afetados, mas o
			// painel React atual nao sabe tratar esse 409 e o Jhonatan ficaria
			// sem conseguir reservar horario nenhum. A melhoria entra depois
			// do corte, junto com a mudanca no front.
			agendar(LocalTime.of(12, 0), List.of(corte));

			criarBloqueio(TERCA, "12:00", "13:00", null).andExpect(status(201));

			// O agendamento continua la, visivel no painel: o bloqueio nao
			// cancela nada.
			assertThat(agenda(TERCA).size()).isEqualTo(1);
		}

		@Test
		@DisplayName("sem sessao devolve 401")
		void semSessao() throws Exception {
			mvc.perform(getComIpProprio(
					"/api/v1/admin/blocked-slots?date=2026-12-15&professionalId=" + jhonatan.getId()))
					.andExpect(status(401));
		}

	}

	// =======================================================================
	// Push subscription
	// =======================================================================

	@Nested
	@DisplayName("POST /api/v1/admin/push-subscription")
	class Push {

		@Test
		@DisplayName("grava a inscricao e devolve 201")
		void grava() throws Exception {
			mvc.perform(postJson("/api/v1/admin/push-subscription", """
					{
					  "endpoint": "https://fcm.googleapis.com/fcm/send/abc123",
					  "keys": { "p256dh": "chave-publica", "auth": "segredo" }
					}
					""").cookie(sessao))
					.andExpect(status(201));

			assertThat(jdbc.queryForObject(
					"SELECT \"p256dh\" FROM \"push_subscriptions\"", String.class))
					.isEqualTo("chave-publica");
		}

		@Test
		@DisplayName("reinscrever o mesmo endpoint atualiza em vez de duplicar")
		void upsert() throws Exception {
			// O navegador reinscreve sozinho quando a inscricao expira; a
			// coluna tem indice unico e um insert cego violaria a constraint.
			for (String chave : List.of("chave-1", "chave-2")) {
				mvc.perform(postJson("/api/v1/admin/push-subscription", """
						{
						  "endpoint": "https://fcm.googleapis.com/fcm/send/abc123",
						  "keys": { "p256dh": "%s", "auth": "segredo" }
						}
						""".formatted(chave)).cookie(sessao))
						.andExpect(status(201));
			}

			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM \"push_subscriptions\"", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject(
					"SELECT \"p256dh\" FROM \"push_subscriptions\"", String.class))
					.isEqualTo("chave-2");
		}

		@Test
		@DisplayName("e rota de admin: sem sessao devolve 401")
		void exigeSessao() throws Exception {
			mvc.perform(postJson("/api/v1/admin/push-subscription", """
					{ "endpoint": "https://x/y", "keys": { "p256dh": "a", "auth": "b" } }
					"""))
					.andExpect(status(401));
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private jakarta.servlet.http.Cookie logar() throws Exception {
		MvcResult login = mvc.perform(postJson("/api/v1/auth/login", """
				{ "email": "%s", "password": "%s" }
				""".formatted(EMAIL, SENHA))).andExpect(status(200)).andReturn();

		String bruto = login.getResponse().getHeaders(org.springframework.http.HttpHeaders.SET_COOKIE)
				.stream()
				.filter(c -> c.startsWith(CookieFactory.COOKIE_ACCESS + "="))
				.findFirst()
				.orElseThrow();

		int inicio = CookieFactory.COOKIE_ACCESS.length() + 1;
		String valor = bruto.substring(inicio, bruto.indexOf(';', inicio));
		return new jakarta.servlet.http.Cookie(CookieFactory.COOKIE_ACCESS, valor);
	}

	/** Cria um agendamento pela API publica e devolve o id. */
	private String agendar(LocalTime hora, List<Service> servicos) throws Exception {
		String ids = servicos.stream()
				.map(s -> "\"" + s.getId() + "\"")
				.collect(java.util.stream.Collectors.joining(","));

		MvcResult criado = mvc.perform(postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": [%s],
				  "scheduledAt": "%sT%s:00-03:00",
				  "guestName": "Maria Aparecida",
				  "guestPhone": "5522999990000"
				}
				""".formatted(jhonatan.getId(), ids, TERCA, hora)))
				.andExpect(status(201)).andReturn();

		return corpoDe(criado).get("data").get("id").asString();
	}

	/** Grava direto no banco, para datas fora da jornada ou do expediente. */
	private void agendarEm(LocalDate dia, LocalTime hora) {
		agendarComStatus(dia, hora, AppointmentStatus.CONFIRMED);
	}

	private void agendarComStatus(LocalDate dia, LocalTime hora, AppointmentStatus status) {
		Appointment agendamento = novoAgendamento(jhonatan, corte, dia, hora, status);
		agendamento.setGuestName("Maria Aparecida");
		agendamento.setGuestPhone("5522999990000");
		gravar(agendamento);
	}

	private JsonNode agenda(LocalDate dia) throws Exception {
		return corpoDe(mvc.perform(
				getComIpProprio("/api/v1/admin/appointments?date=" + dia).cookie(sessao))
				.andExpect(status(200)).andReturn()).get("data");
	}

	private JsonNode stats() throws Exception {
		return corpoDe(mvc.perform(getComIpProprio("/api/v1/admin/stats").cookie(sessao))
				.andExpect(status(200)).andReturn()).get("data");
	}

	private ResultActions mudarStatus(String id, String status) throws Exception {
		return mvc.perform(putJson("/api/v1/admin/appointments/" + id + "/status",
				"{\"status\":\"%s\"}".formatted(status)).cookie(sessao));
	}

	private ResultActions criarBloqueio(LocalDate dia, String inicio, String fim, String motivo)
			throws Exception {

		String razao = (motivo != null) ? "\"reason\": \"%s\",".formatted(motivo) : "";
		return mvc.perform(postJson("/api/v1/admin/blocked-slots", """
				{
				  "professionalId": "%s",
				  "date": "%s",
				  %s
				  "startTime": "%s",
				  "endTime": "%s"
				}
				""".formatted(jhonatan.getId(), dia, razao, inicio, fim)).cookie(sessao));
	}

	private List<String> horariosLivres() throws Exception {
		JsonNode corpo = corpoDe(mvc.perform(postJson("/api/v1/available-times", """
				{
				  "professionalId": "%s",
				  "serviceId": "%s",
				  "date": "%s",
				  "totalDurationMinutes": 50
				}
				""".formatted(jhonatan.getId(), corte.getId(), TERCA)))
				.andExpect(status(200)).andReturn());

		return corpo.get("data").get("slots").valueStream().map(JsonNode::asString).toList();
	}

	private int reservar(LocalTime hora) throws Exception {
		return mvc.perform(postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": ["%s"],
				  "scheduledAt": "%sT%s:00-03:00",
				  "guestName": "Outro Cliente",
				  "guestPhone": "5522988880000"
				}
				""".formatted(jhonatan.getId(), corte.getId(), TERCA, hora)))
				.andReturn().getResponse().getStatus();
	}

	private com.cauacosenza.barbearia.service.SlotQueryMapper mapperDeTeste() {
		return this.mapper;
	}

	private JsonNode corpoDe(MvcResult resultado) throws Exception {
		return this.json.readTree(resultado.getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static org.springframework.test.web.servlet.ResultMatcher status(int esperado) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(esperado);
	}

}
