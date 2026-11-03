package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.cauacosenza.barbearia.domain.entity.AdminUser;
import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.repository.AdminUserRepository;
import com.cauacosenza.barbearia.security.CookieFactory;

import tools.jackson.databind.JsonNode;

/**
 * Concluir atendimento: o que e barrado, e o que o termino antecipado devolve.
 *
 * <h2>As duas faces de "COMPLETED nao ocupa agenda"</h2>
 *
 * <p>{@code OCUPAM_AGENDA} e {@code {PENDING, CONFIRMED}}. Dessa unica regra
 * sairiam um recurso e um bug — e a medicao mostra que so o bug e real.
 *
 * <p><b>O bug, fechado aqui:</b> concluir um atendimento que ainda nao
 * aconteceu liberava um horario que esta de fato vendido. O cliente original
 * aparece, e o horario dele foi oferecido a outro. Um clique errado no painel
 * virava dois clientes na porta no mesmo minuto.
 *
 * <p><b>O recurso, que NAO existe:</b> terminar um corte de 50 minutos em 30
 * nao devolve os outros 20 para a grade. O intervalo deixa de bloquear, mas o
 * passo da grade e fixo em 50 e a contagem parte da borda do vao, entao
 * nenhum ponto novo aparece dentro do tempo liberado. Os testes de
 * {@link TerminoAntecipado} medem isso, e o javadoc da classe explica o que
 * precisaria mudar para o pedido do Jhonatan ser atendido.
 */
class ConclusaoDeAtendimentoTest extends AbstractPostgresIntegrationTest {

	private static final String EMAIL = "admin@barbearia.com";

	private static final String SENHA = "Admin@123456";

	/** Terca, 15/12/2026, 08:00 em Brasilia: antes da abertura. */
	private static final Instant ANTES_DA_ABERTURA = Instant.parse("2026-12-15T11:00:00Z");

	/** A mesma terca, 09:10 em Brasilia: o corte das 08:40 terminou cedo. */
	private static final Instant AS_09_10 = Instant.parse("2026-12-15T12:10:00Z");

	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdminUserRepository usuarios;

	@Autowired
	private PasswordEncoder senhas;

	private Barber jhonatan;

	private String corte50;

	private Cookie sessao;

	@BeforeEach
	void prepararPainel() throws Exception {
		relogioEm(ANTES_DA_ABERTURA);

		AdminUser admin = new AdminUser();
		admin.setEmail(EMAIL);
		admin.setPasswordHash(this.senhas.encode(SENHA));
		admin.setName("Jhonatan Correa");
		admin.setPhone("5522999990000");
		admin.setRole(Role.ADMIN);
		this.usuarios.saveAndFlush(admin);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corte50 = novoServico("Corte Máquina e Tesoura", 50).getId();

		for (int dia = 2; dia <= 4; dia++) {
			BarberSchedule jornada = new BarberSchedule();
			jornada.setProfessional(this.jhonatan);
			jornada.setDayOfWeek(dia);
			jornada.setStartTime("08:40");
			jornada.setEndTime("19:30");
			this.schedules.saveAndFlush(jornada);
		}

		this.sessao = logar();
	}

	// =======================================================================
	// Concluir antes da hora: recusado
	// =======================================================================

	@Nested
	@DisplayName("concluir atendimento que ainda nao comecou")
	class AntesDaHora {

		@Test
		@DisplayName("responde 409 e nomeia as duas saidas legitimas")
		void recusadoCom409() throws Exception {
			String id = agendar("13:00");

			ResultActions r = mudarStatus(id, "COMPLETED");

			assertThat(codigo(r)).isEqualTo(409);
			JsonNode corpo = corpoDe(r);
			assertThat(corpo.get("success").asBoolean()).isFalse();
			assertThat(corpo.get("error").asString()).isEqualTo("Conflict");
			assertThat(corpo.get("message").asString()).isEqualTo(
					"Não é possível concluir um atendimento que ainda não começou. "
							+ "Se o cliente desmarcou, use CANCELLED.");
		}

		@Test
		@DisplayName("o status no banco nao muda")
		void statusPreservado() throws Exception {
			String id = agendar("13:00");

			mudarStatus(id, "COMPLETED");

			assertThat(appointments.findById(id).orElseThrow().getStatus())
					.isEqualTo(AppointmentStatus.PENDING);
		}

		@Test
		@DisplayName("e o horario NAO volta para a grade")
		void horarioSegueOcupado() throws Exception {
			// A asercao que importa: sem ela, as duas acima passariam e o
			// cliente continuaria podendo ser dublado.
			String id = agendar("13:00");
			assertThat(horarios()).doesNotContain("13:00");

			mudarStatus(id, "COMPLETED");

			assertThat(horarios()).doesNotContain("13:00");
		}

		@Test
		@DisplayName("a saida sugerida pela mensagem funciona, e libera o horario")
		void saidaSugeridaFunciona() throws Exception {
			// CANCELLED e a unica saida para atendimento futuro, e liberar o
			// horario e justamente o ponto dela -- ao contrario de COMPLETED e
			// NO_SHOW, onde liberar era o estrago.
			String id = agendar("13:00");
			// Com o agendamento de pe o dia tem 11 pontos: ele ocupa
			// 13:00-13:50 e parte a grade em dois vaos.
			assertThat(horarios()).hasSize(11);

			assertThat(codigo(mudarStatus(id, "CANCELLED"))).isEqualTo(200);

			// Liberado, o dia volta aos 13 pontos canonicos. O 13:00 em si nao
			// reaparece porque nunca foi ponto de grade -- este agendamento
			// foi criado direto no repositorio, fora da grade.
			assertThat(horarios()).hasSize(13);
			assertThat(horarios()).contains("12:50", "13:40");
		}

		@Test
		@DisplayName("NO_SHOW futuro tambem responde 409, com a mesma estrutura")
		void faltaFuturaRecusada() throws Exception {
			// Mesmo buraco pela mesma porta: NO_SHOW tambem esta fora de
			// OCUPAM_AGENDA.
			String id = agendar("13:00");

			ResultActions r = mudarStatus(id, "NO_SHOW");

			assertThat(codigo(r)).isEqualTo(409);
			JsonNode corpo = corpoDe(r);
			assertThat(corpo.get("error").asString()).isEqualTo("Conflict");
			assertThat(corpo.get("message").asString()).isEqualTo(
					"Não é possível registrar falta em um atendimento que ainda não começou. "
							+ "Se o cliente desmarcou, use CANCELLED.");
			assertThat(appointments.findById(id).orElseThrow().getStatus())
					.isEqualTo(AppointmentStatus.PENDING);
			assertThat(horarios()).doesNotContain("13:00");
		}

		@Test
		@DisplayName("NO_SHOW: um minuto antes recusa, no minuto exato aceita")
		void bordasDoNoShow() throws Exception {
			String antes = agendar("13:00");
			relogioEm(Instant.parse("2026-12-15T15:59:00Z")); // 12:59 BRT
			renovarSessao();
			assertThat(codigo(mudarStatus(antes, "NO_SHOW"))).isEqualTo(409);

			relogioEm(Instant.parse("2026-12-15T16:00:00Z")); // 13:00 BRT
			renovarSessao();
			assertThat(codigo(mudarStatus(antes, "NO_SHOW"))).isEqualTo(200);
		}

		@Test
		@DisplayName("CONFIRMED num atendimento futuro continua permitido")
		void confirmarSegueLiberado() throws Exception {
			// A regra barra COMPLETED, nao qualquer mudanca: confirmar
			// agendamento futuro e o que o painel faz todo dia.
			String id = agendar("13:00");

			assertThat(codigo(mudarStatus(id, "CONFIRMED"))).isEqualTo(200);
			assertThat(appointments.findById(id).orElseThrow().getStatus())
					.isEqualTo(AppointmentStatus.CONFIRMED);
			// E segue ocupando: CONFIRMED esta em OCUPAM_AGENDA.
			assertThat(horarios()).doesNotContain("13:00");
		}

	}

	// =======================================================================
	// Concluir depois da hora: aceito
	// =======================================================================

	@Nested
	@DisplayName("concluir atendimento que ja comecou")
	class DepoisDaHora {

		@Test
		@DisplayName("responde 200 e grava COMPLETED")
		void aceito() throws Exception {
			String id = agendar("08:40");

			relogioEm(AS_09_10);
			renovarSessao();

			assertThat(codigo(mudarStatus(id, "COMPLETED"))).isEqualTo(200);
			assertThat(appointments.findById(id).orElseThrow().getStatus())
					.isEqualTo(AppointmentStatus.COMPLETED);
		}

		@Test
		@DisplayName("no minuto exato do horario marcado ja e permitido")
		void bordaDoHorario() throws Exception {
			// A regra e `scheduledAt.isAfter(agora)`: as 13:00 em ponto o
			// atendimento nao esta mais no futuro. A borda e deliberada --
			// recusar no proprio minuto obrigaria o barbeiro a esperar para
			// registrar o que ja esta fazendo.
			String id = agendar("13:00");

			relogioEm(Instant.parse("2026-12-15T16:00:00Z"));
			renovarSessao(); // 13:00 BRT

			assertThat(codigo(mudarStatus(id, "COMPLETED"))).isEqualTo(200);
		}

		@Test
		@DisplayName("um segundo depois do horario tambem")
		void umSegundoDepois() throws Exception {
			String id = agendar("13:00");

			relogioEm(Instant.parse("2026-12-15T16:00:01Z"));
			renovarSessao();

			assertThat(codigo(mudarStatus(id, "COMPLETED"))).isEqualTo(200);
		}

		@Test
		@DisplayName("um minuto ANTES e recusado")
		void umMinutoAntes() throws Exception {
			// O outro lado da borda, para a regra nao poder ser invertida sem
			// quebrar teste.
			String id = agendar("13:00");

			relogioEm(Instant.parse("2026-12-15T15:59:00Z"));
			renovarSessao(); // 12:59 BRT

			assertThat(codigo(mudarStatus(id, "COMPLETED"))).isEqualTo(409);
		}

	}

	// =======================================================================
	// Termino antecipado: o que o Jhonatan pediu NAO acontece
	// =======================================================================

	@Nested
	@DisplayName("termino antecipado NAO devolve horario oferecivel")
	class TerminoAntecipado {

		@Test
		@DisplayName("corte 08:40-09:30 concluido as 09:10: a grade nao muda em nada")
		void concluirCedoNaoAbreHorario() throws Exception {
			String id = agendar("08:40");
			relogioEm(AS_09_10);
			renovarSessao();

			List<String> comPendente = horarios();

			assertThat(codigo(mudarStatus(id, "COMPLETED"))).isEqualTo(200);

			List<String> comConcluido = horarios();

			// Identicas. O intervalo 09:10-09:30 deixou de bloquear, mas
			// nenhum ponto da grade cai dentro dele: os pontos sao 08:40 +
			// 50k, e entre 09:10 e 09:30 nao ha nenhum.
			assertThat(comConcluido).isEqualTo(comPendente);
			assertThat(comConcluido).containsExactly(
					"09:30", "10:20", "11:10", "12:00", "12:50", "13:40",
					"14:30", "15:20", "16:10", "17:00", "17:50", "18:40");

			// O primeiro horario oferecido e 09:30 nos dois casos -- o fim
			// ORIGINAL do atendimento, nao o fim real.
			assertThat(comConcluido).startsWith("09:30");
		}

		@Test
		@DisplayName("nem terminando muito cedo: concluido as 08:45, o primeiro segue 09:30")
		void nemTerminandoMuitoCedo() throws Exception {
			// 08:45 libera 45 dos 50 minutos. Com a antecedencia minima de 15
			// minutos o corte fica em 09:00, e o primeiro ponto da grade em
			// 09:00 ou depois continua sendo 09:30.
			String id = agendar("08:40");
			relogioEm(Instant.parse("2026-12-15T11:45:00Z"));
			renovarSessao(); // 08:45 BRT

			assertThat(codigo(mudarStatus(id, "COMPLETED"))).isEqualTo(200);

			assertThat(horarios()).startsWith("09:30");
			assertThat(horarios()).hasSize(12);
		}

		@Test
		@DisplayName("o horario concluido volta a ser oferecido em dia futuro, e isso e esperado")
		void concluidoNaoOcupaMesmo() throws Exception {
			// Prova que COMPLETED realmente nao ocupa -- o mecanismo existe, o
			// que falta e um ponto de grade dentro do tempo liberado. Aqui o
			// atendimento inteiro e liberado, e o 08:40 reaparece.
			String id = agendar("08:40");
			relogioEm(AS_09_10);
			renovarSessao();
			assertThat(horarios()).doesNotContain("08:40");

			mudarStatus(id, "COMPLETED");

			// Num dia sem o filtro de antecedencia (quarta, 16/12), o 08:40
			// liberado aparece.
			assertThat(horariosDe(LocalDate.of(2026, 12, 16))).contains("08:40");
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	/**
	 * Move o relogio da aplicacao.
	 *
	 * <p>Quem avanca o relogio depois do login precisa chamar
	 * {@link #renovarSessao()}: o access token vive 15 minutos pelo MESMO
	 * relogio, entao pular para 09:10 depois de logar as 08:00 expira a sessao
	 * e o painel responde 401. Foi o que aconteceu na primeira versao destes
	 * testes — o 401 parecia bug de autorizacao e era o relogio.
	 */
	private void relogioEm(Instant quando) {
		BDDMockito.given(this.clock.instant()).willReturn(quando);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);
	}

	private void renovarSessao() throws Exception {
		this.sessao = logar();
	}

	/**
	 * Cria direto pelo repositorio, com duracao de 50 minutos.
	 *
	 * <p>O caminho HTTP de criacao tem teste proprio; aqui ele seria ruido, e
	 * ainda esbarraria no rate limit depois de alguns casos.
	 */
	private String agendar(String hora) {
		com.cauacosenza.barbearia.domain.entity.Service servico =
				this.services.findById(this.corte50).orElseThrow();

		Appointment agendamento = novoAgendamento(this.jhonatan, servico,
				TERCA, LocalTime.parse(hora), AppointmentStatus.PENDING);

		// O item NAO e decorativo: gravar() recalcula slot_duration_minutes
		// pelo mapper, e sem BookedService nenhum ele devolve o padrao de 30.
		// Um agendamento de 30 minutos em vez de 50 muda a borda do vao e,
		// com ela, a grade inteira -- foi o que fez a primeira versao destes
		// testes medir 10:00 onde devia medir 09:30.
		agendamento.adicionarServico(item(servico, 50));

		return gravar(agendamento).getId();
	}

	private ResultActions mudarStatus(String id, String status) throws Exception {
		return this.mvc.perform(putJson("/api/v1/admin/appointments/" + id + "/status",
				"{\"status\": \"" + status + "\"}").cookie(this.sessao));
	}

	private List<String> horarios() throws Exception {
		return horariosDe(TERCA);
	}

	private List<String> horariosDe(LocalDate dia) throws Exception {
		JsonNode corpo = corpoDe(this.mvc.perform(postJson("/api/v1/available-times", """
				{
				  "professionalId": "%s",
				  "serviceId": "%s",
				  "date": "%s",
				  "totalDurationMinutes": 50
				}
				""".formatted(this.jhonatan.getId(), this.corte50, dia))));

		assertThat(corpo.get("success").asBoolean()).as("consulta falhou: %s", corpo).isTrue();
		return corpo.get("data").get("slots").valueStream().map(JsonNode::asString).toList();
	}

	private Cookie logar() throws Exception {
		MvcResult login = this.mvc.perform(postJson("/api/v1/auth/login", """
				{ "email": "%s", "password": "%s" }
				""".formatted(EMAIL, SENHA))).andReturn();

		String bruto = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(c -> c.startsWith(CookieFactory.COOKIE_ACCESS + "="))
				.findFirst()
				.orElseThrow();

		int inicio = CookieFactory.COOKIE_ACCESS.length() + 1;
		return new Cookie(CookieFactory.COOKIE_ACCESS, bruto.substring(inicio, bruto.indexOf(';', inicio)));
	}

	private static int codigo(ResultActions r) throws Exception {
		return r.andReturn().getResponse().getStatus();
	}

	private JsonNode corpoDe(ResultActions resultado) throws Exception {
		return this.json.readTree(
				resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

}
