package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;

import tools.jackson.databind.JsonNode;

/**
 * Quanto um atendimento curto custa na grade de 50 minutos.
 *
 * <h2>O que este teste mede</h2>
 *
 * <p>O passo da grade e a <b>duracao pedida</b>, capada em 50 minutos
 * ({@code SlotCalculator} linha 118), e os horarios sao gerados <b>de dentro de
 * cada vao livre</b>, a partir da borda do vao (linha 128). Um agendamento
 * parte o dia em dois vaos, e o segundo vao recomeca a contagem na hora em que
 * aquele atendimento termina — nao na grade original.
 *
 * <p>A consequencia e que um atendimento de 3 minutos desloca a tarde inteira.
 * Este teste nao julga se isso esta certo: mede o tamanho do efeito, para que
 * a decisao seja tomada com numero na mao.
 *
 * <p>Nao e teste de regressao: e documentacao executavel do comportamento
 * atual. Se a politica mudar, ele quebra, e e isso que se quer.
 */
class FragmentacaoDaGradeTest extends AbstractPostgresIntegrationTest {

	/** Segunda, 14/12/2026, 09:00 em Brasilia — longe das datas medidas. */
	private static final Instant AGORA = Instant.parse("2026-12-14T12:00:00Z");

	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	private static final LocalDate QUARTA = LocalDate.of(2026, 12, 16);

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	private Barber jhonatan;

	private String corte50;

	private String sobrancelha3;

	@BeforeEach
	void prepararCatalogo() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corte50 = novoServico("Corte Máquina e Tesoura", 50).getId();
		this.sobrancelha3 = novoServico("Sobrancelha", 3).getId();

		// Terca a quinta, 08:40-19:30: a jornada real.
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

	@Test
	@DisplayName("a grade intacta de um dia tem 13 slots de 50 minutos")
	void gradeIntacta() throws Exception {
		List<String> slots = grade(TERCA, corte50, 50);

		assertThat(slots).hasSize(13);
		assertThat(slots).containsExactly(
				"08:40", "09:30", "10:20", "11:10", "12:00", "12:50", "13:40",
				"14:30", "15:20", "16:10", "17:00", "17:50", "18:40");
	}

	@Test
	@DisplayName("uma Sobrancelha de 3 minutos as 13:00 desloca a tarde inteira")
	void sobrancelhaDeTresMinutosFragmentaODia() throws Exception {
		List<String> antes = grade(TERCA, corte50, 50);

		agendar(TERCA, "13:00", sobrancelha3).andExpect(status().isCreated());

		List<String> depois = grade(TERCA, corte50, 50);

		// O que a conta faz: o agendamento ocupa [13:00, 13:03). Sobram dois
		// vaos -- 08:40-13:00 (260 min) e 13:03-19:30 (387 min) --, e cada um
		// gera slots a partir da PROPRIA borda.
		assertThat(depois).containsExactly(
				// vao 1: cinco cabem; 12:50 nao, porque terminaria 13:40
				"08:40", "09:30", "10:20", "11:10", "12:00",
				// vao 2: recomeca em 13:03, e toda a tarde herda o deslocamento
				"13:03", "13:53", "14:43", "15:33", "16:23", "17:13", "18:03");

		List<String> perdidos = antes.stream().filter(h -> !depois.contains(h)).toList();
		List<String> novos = depois.stream().filter(h -> !antes.contains(h)).toList();

		// O numero que importa nao e o saldo: e quantos horarios da grade
		// original deixaram de existir.
		assertThat(perdidos).containsExactly(
				"12:50", "13:40", "14:30", "15:20", "16:10", "17:00", "17:50", "18:40");
		assertThat(perdidos).hasSize(8);
		assertThat(novos).hasSize(7);
		assertThat(depois).hasSize(12);

		// Saldo de capacidade: um slot. Saldo de horarios reconheciveis: oito.
		assertThat(antes.size() - depois.size()).isEqualTo(1);

		// Nenhum dos sete novos cai em minuto redondo da grade de 50.
		assertThat(novos).allSatisfy(h -> assertThat(antes).doesNotContain(h));
	}

	@Test
	@DisplayName("o mesmo horario com um Corte de 50 minutos nao desloca nada")
	void agendamentoAlinhadoNaoFragmenta() throws Exception {
		// Controle: o estrago nao vem de "ter um agendamento", vem de o
		// agendamento nao terminar num ponto da grade.
		agendar(TERCA, "12:00", corte50).andExpect(status().isCreated());

		List<String> depois = grade(TERCA, corte50, 50);

		assertThat(depois).containsExactly(
				"08:40", "09:30", "10:20", "11:10", "12:50", "13:40",
				"14:30", "15:20", "16:10", "17:00", "17:50", "18:40");
		// Perdeu exatamente o horario vendido. Nenhum outro mudou.
		assertThat(depois).hasSize(12);
	}

	@Test
	@DisplayName("um bloqueio manual de 3 minutos NAO desloca a grade")
	void bloqueioNaoRealinha() throws Exception {
		// O contraste que o javadoc do SlotCalculator registra: bloqueio nao
		// entra na formacao dos vaos, e so filtra no fim. Mesmos tres minutos,
		// efeito completamente diferente.
		ScheduleBlock bloqueio = new ScheduleBlock();
		bloqueio.setProfessional(this.jhonatan);
		bloqueio.setDate(QUARTA.toString());
		bloqueio.setStartTime("13:00");
		bloqueio.setEndTime("13:03");
		this.blocks.saveAndFlush(bloqueio);

		List<String> depois = grade(QUARTA, corte50, 50);

		// A grade segue alinhada; so o slot que colide com os tres minutos sai.
		assertThat(depois).containsExactly(
				"08:40", "09:30", "10:20", "11:10", "12:00", "13:40",
				"14:30", "15:20", "16:10", "17:00", "17:50", "18:40");
		assertThat(depois).allSatisfy(h -> assertThat(h).matches("\\d\\d:(00|10|20|30|40|50)"));
		assertThat(depois).hasSize(12);
	}

	@Test
	@DisplayName("duas Sobrancelhas em horarios quebrados fragmentam em tres grades")
	void duasSobrancelhasEmTresGrades() throws Exception {
		// O caso que o cliente vai viver: dois atendimentos curtos num dia.
		agendar(QUARTA, "10:00", sobrancelha3).andExpect(status().isCreated());
		agendar(QUARTA, "15:00", sobrancelha3).andExpect(status().isCreated());

		List<String> depois = grade(QUARTA, corte50, 50);

		assertThat(depois).containsExactly(
				// vao 1: 08:40-10:00 (80 min) -- cabe um
				"08:40",
				// vao 2: 10:03-15:00 (297 min) -- cabem cinco. 14:13 NAO entra:
				// terminaria 15:03, dentro do segundo agendamento.
				"10:03", "10:53", "11:43", "12:33", "13:23",
				// vao 3: 15:03-19:30 (267 min) -- cabem cinco
				"15:03", "15:53", "16:43", "17:33", "18:23");

		// Onze, nao doze: seis minutos de atendimento custaram DOIS slots de
		// capacidade, alem de deslocar tudo.
		assertThat(depois).hasSize(11);
		// Dos treze originais, sobrou UM.
		assertThat(depois.stream().filter(h -> h.endsWith(":40") || h.endsWith(":30")
				|| h.endsWith(":20") || h.endsWith(":10") || h.endsWith(":00")
				|| h.endsWith(":50")).toList()).containsExactly("08:40");
	}

	@Test
	@DisplayName("o passo e fixo em 50: a Sobrancelha oferece os mesmos 13 pontos do Corte")
	void passoFixoParaServicoCurto() throws Exception {
		// Antes desta regra o passo era a duracao pedida, e a Sobrancelha de 3
		// minutos gerava 216 horarios de tres em tres -- 08:40, 08:43, 08:46,
		// ... 19:25 --, oferecendo encaixe no meio de qualquer tempo livre.
		List<String> deTres = grade(TERCA, sobrancelha3, 3);
		List<String> deCinquenta = grade(TERCA, corte50, 50);

		assertThat(deTres).hasSize(13);
		// Mesmos pontos de encaixe do servico longo: o que muda e quanto de
		// cada encaixe o atendimento consome, nao onde ele pode comecar.
		assertThat(deTres).isEqualTo(deCinquenta);
		assertThat(deTres).containsExactly(
				"08:40", "09:30", "10:20", "11:10", "12:00", "12:50", "13:40",
				"14:30", "15:20", "16:10", "17:00", "17:50", "18:40");
	}

	@Test
	@DisplayName("um servico curto cabe onde um longo nao cabe, no fim do vao")
	void curtoCabeOndeLongoNaoCabe() throws Exception {
		// O passo fixo nao faz curto e longo oferecerem SEMPRE a mesma lista:
		// a checagem de caber continua usando a duracao real. A divergencia
		// aparece quando o vao nao e multiplo de 50.
		//
		// Uma Sobrancelha as 08:40 ocupa tres minutos, e o vao seguinte tem
		// 647 minutos -- 12 passos de 50 sobram 47. Nesse resto cabe um
		// atendimento de 3 minutos e nao um de 50.
		agendar(QUARTA, "08:40", sobrancelha3).andExpect(status().isCreated());

		List<String> deTres = grade(QUARTA, sobrancelha3, 3);
		List<String> deCinquenta = grade(QUARTA, corte50, 50);

		assertThat(deTres).hasSize(13);
		assertThat(deCinquenta).hasSize(12);
		// Os dois comecam no mesmo ponto: a borda do vao.
		assertThat(deTres).startsWith("08:43");
		assertThat(deCinquenta).startsWith("08:43");
		// O 13o ponto so existe para quem cabe nele.
		assertThat(deTres).endsWith("18:43");
		assertThat(deCinquenta).endsWith("17:53");
		assertThat(deTres).containsAll(deCinquenta);
	}

	// =======================================================================

	private List<String> grade(LocalDate dia, String servicoId, int duracao) throws Exception {
		JsonNode corpo = corpoDe(this.mvc.perform(postJson("/api/v1/available-times", """
				{
				  "professionalId": "%s",
				  "serviceId": "%s",
				  "date": "%s",
				  "totalDurationMinutes": %d
				}
				""".formatted(this.jhonatan.getId(), servicoId, dia, duracao))));

		assertThat(corpo.get("success").asBoolean()).as("consulta falhou: %s", corpo).isTrue();
		return corpo.get("data").get("slots").valueStream().map(JsonNode::asString).toList();
	}

	private ResultActions agendar(LocalDate dia, String hora, String servicoId) throws Exception {
		return this.mvc.perform(postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": ["%s"],
				  "scheduledAt": "%sT%s:00-03:00",
				  "guestName": "Cliente de Teste",
				  "guestPhone": "5522999990000"
				}
				""".formatted(this.jhonatan.getId(), servicoId, dia, hora)));
	}

	private JsonNode corpoDe(ResultActions resultado) throws Exception {
		return this.json.readTree(
				resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

}
