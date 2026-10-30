package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.service.ChatMessagePort;
import com.cauacosenza.barbearia.service.ChatMessagePort.ChatMessage;

import tools.jackson.databind.JsonNode;

/**
 * O fluxo de notificacao, com o {@link ChatMessagePort} mockado.
 *
 * <p>Aqui esta <b>o teste mais importante desta fase</b>:
 * {@link Resiliencia#falhaNoEnvioNaoImpedeOAgendamento()}. Se o WhatsApp
 * puder derrubar uma reserva, a integracao e um passivo e nao um recurso — o
 * cliente perde o horario porque um servico de terceiro estava fora do ar.
 *
 * <p>O {@code FRONTEND_URL} e o {@code BARBER_PHONE} vem de
 * {@code @TestPropertySource} porque o link de cancelamento e o destino do
 * aviso ao barbeiro dependem deles, e o teste precisa de valores conhecidos
 * para conferir o conteudo.
 */
@TestPropertySource(properties = {
		"FRONTEND_URL=https://barbeariajhonatan.com",
		"BARBER_PHONE=5522992718402",
})
class NotificacaoDeAgendamentoTest extends AbstractPostgresIntegrationTest {

	/** Terca-feira. */
	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	/** 2026-12-15, 03:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	private static final String TELEFONE_DO_BARBEIRO = "5522992718402";

	@MockitoBean(name = "clock")
	private Clock clock;

	/**
	 * A porta, mockada.
	 *
	 * <p>E isto que a inversao de dependencia compra: o fluxo inteiro de
	 * agendamento e exercitado contra Postgres de verdade, e nenhuma linha de
	 * HTTP ou de Evolution entra no caminho.
	 */
	@MockitoBean
	private ChatMessagePort whatsapp;

	@Autowired
	private MockMvc mvc;

	private Barber jhonatan;

	private Service corte;

	private Service barba;

	@BeforeEach
	void prepararAgenda() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);
		// Por default o envio da certo.
		given(this.whatsapp.enviar(any())).willReturn(true);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corte = novoServico("Corte Máquina e Tesoura", 50);
		this.barba = novoServico("Barba Simples", 15);

		BarberSchedule terca = new BarberSchedule();
		terca.setProfessional(this.jhonatan);
		terca.setDayOfWeek(2);
		terca.setStartTime("08:40");
		terca.setEndTime("19:30");
		this.schedules.saveAndFlush(terca);
	}

	// =======================================================================
	// As duas mensagens do agendamento
	// =======================================================================

	@Nested
	@DisplayName("ao criar um agendamento")
	class AoCriar {

		@Test
		@DisplayName("dispara exatamente DUAS mensagens: cliente e barbeiro")
		void duasMensagens() throws Exception {
			agendar(LocalTime.of(10, 20), List.of(corte, barba));

			List<ChatMessage> enviadas = capturarEnviadas(2);

			assertThat(enviadas).extracting(ChatMessage::telefone)
					.containsExactly("5522999990000", TELEFONE_DO_BARBEIRO);
		}

		@Test
		@DisplayName("a mensagem do cliente traz o link com o cancelToken correto")
		void linkComOToken() throws Exception {
			JsonNode criado = agendar(LocalTime.of(10, 20), List.of(corte));
			String token = criado.get("data").get("cancelToken").asString();

			ChatMessage aoCliente = capturarEnviadas(2).get(0);

			assertThat(aoCliente.texto())
					.contains("https://barbeariajhonatan.com/cancelar/" + token)
					.contains("Seu agendamento foi confirmado")
					.contains("*1 hora de antecedência*");
			assertThat(token).isNotBlank();
		}

		@Test
		@DisplayName("a mensagem do cliente tem a data, a hora e o total certos")
		void conteudoDaMensagemDoCliente() throws Exception {
			agendar(LocalTime.of(10, 20), List.of(corte, barba));

			ChatMessage aoCliente = capturarEnviadas(2).get(0);

			assertThat(aoCliente.texto())
					.contains("Olá, *Maria Aparecida*! 🎉")
					.contains("terça-feira, 15 de dezembro de 2026")
					.contains("*10:20*")
					.contains("Corte Máquina e Tesoura + Barba Simples")
					// 50 + 15 servicos = R$ 100,00 (dois a 5000 centavos).
					.contains("💰 *Total:* R$ 100,00");
		}

		@Test
		@DisplayName("a mensagem do barbeiro traz o telefone do cliente formatado")
		void conteudoDaMensagemDoBarbeiro() throws Exception {
			agendar(LocalTime.of(10, 20), List.of(corte));

			ChatMessage aoBarbeiro = capturarEnviadas(2).get(1);

			assertThat(aoBarbeiro.texto())
					.startsWith("🔔 *Novo agendamento!*")
					.contains("👤 *Cliente:* Maria Aparecida")
					.contains("📱 *WhatsApp:* (22) 99999-0000")
					.contains("🕐 *Horário:* 10:20");
		}

		@Test
		@DisplayName("marca confirmation_sent e confirmation_sent_at quando o cliente é avisado")
		void marcaConfirmacao() throws Exception {
			JsonNode criado = agendar(LocalTime.of(10, 20), List.of(corte));
			String id = criado.get("data").get("id").asString();

			Appointment gravado = appointments.findById(id).orElseThrow();
			assertThat(gravado.isConfirmationSent()).isTrue();
			assertThat(gravado.getConfirmationSentAt()).isNotNull();
		}

		@Test
		@DisplayName("envio recusado (false) NÃO marca confirmation_sent")
		void naoMarcaQuandoRecusado() throws Exception {
			// Evolution respondeu 500, ou nao esta configurada. A coluna tem
			// que refletir isso, para o reenvio manual achar quem ficou sem
			// aviso.
			given(whatsapp.enviar(any())).willReturn(false);

			JsonNode criado = agendar(LocalTime.of(10, 20), List.of(corte));
			String id = criado.get("data").get("id").asString();

			Appointment gravado = appointments.findById(id).orElseThrow();
			assertThat(gravado.isConfirmationSent()).isFalse();
			assertThat(gravado.getConfirmationSentAt()).isNull();
		}

	}

	// =======================================================================
	// Resiliência — o teste que define a fase
	// =======================================================================

	@Nested
	@DisplayName("resiliência: WhatsApp nunca derruba um agendamento")
	class Resiliencia {

		@Test
		@DisplayName("FALHA NO ENVIO NÃO IMPEDE O AGENDAMENTO")
		void falhaNoEnvioNaoImpedeOAgendamento() throws Exception {
			// O port promete nao lancar, mas aqui ele lanca de proposito: e a
			// unica forma de provar que a transacao do agendamento esta
			// protegida mesmo de uma implementacao futura que quebre a
			// promessa.
			given(whatsapp.enviar(any()))
					.willThrow(new RuntimeException("Evolution API fora do ar"));

			JsonNode criado = agendar(LocalTime.of(10, 20), List.of(corte));

			// A requisicao deu 201 e o registro esta no banco.
			String id = criado.get("data").get("id").asString();
			assertThat(appointments.findById(id)).isPresent();
			assertThat(appointments.count()).isEqualTo(1);

			Appointment gravado = appointments.findById(id).orElseThrow();
			assertThat(gravado.getStatus()).isEqualTo(AppointmentStatus.PENDING);
			assertThat(gravado.getGuestName()).isEqualTo("Maria Aparecida");
			// Nao foi avisado, e a coluna diz isso.
			assertThat(gravado.isConfirmationSent()).isFalse();

			// E o horario foi de fato ocupado: o agendamento vale.
			assertThat(horariosLivres()).doesNotContain("10:20");
		}

		@Test
		@DisplayName("a exceção do envio não virou 500 — a resposta foi 201")
		void respostaContinua201() throws Exception {
			given(whatsapp.enviar(any()))
					.willThrow(new IllegalStateException("timeout"));

			// Uma excecao escapando de um ouvinte AFTER_COMMIT nao desfaz o
			// commit (a transacao ja acabou) mas subiria pela pilha e viraria
			// 500 numa requisicao que deu certo: o cliente veria erro depois
			// de o horario ter sido reservado.
			mvc.perform(pedidoDeAgendamento(LocalTime.of(10, 20), List.of(corte)))
					.andExpect(status(201));
		}

		@Test
		@DisplayName("a falha do aviso ao barbeiro não impede o aviso ao cliente")
		void falhaDeUmNaoImpedeOOutro() throws Exception {
			given(whatsapp.enviar(any())).willAnswer(invocacao -> {
				ChatMessage mensagem = invocacao.getArgument(0);
				if (mensagem.telefone().equals(TELEFONE_DO_BARBEIRO)) {
					throw new RuntimeException("falhou so o do barbeiro");
				}
				return true;
			});

			JsonNode criado = agendar(LocalTime.of(10, 20), List.of(corte));
			String id = criado.get("data").get("id").asString();

			// As duas tentativas aconteceram, e a do cliente deu certo.
			capturarEnviadas(2);
			assertThat(appointments.findById(id).orElseThrow().isConfirmationSent()).isTrue();
		}

		@Test
		@DisplayName("agendamento recusado pela constraint NÃO dispara mensagem")
		void agendamentoRecusadoNaoNotifica() throws Exception {
			agendar(LocalTime.of(10, 20), List.of(corte));
			org.mockito.Mockito.reset(whatsapp);
			given(whatsapp.enviar(any())).willReturn(true);

			// O segundo pedido no mesmo horario perde para a constraint de
			// exclusao da V2. Como o evento so e entregue APOS O COMMIT, nao
			// se manda "agendamento confirmado" de um agendamento que nao
			// existe -- que e o que um envio dentro da transacao faria.
			mvc.perform(pedidoDeAgendamento(LocalTime.of(10, 20), List.of(corte)))
					.andExpect(status(409));

			verifyNoInteractions(whatsapp);
			assertThat(appointments.count()).isEqualTo(1);
		}

		@Test
		@DisplayName("validação recusada NÃO dispara mensagem")
		void validacaoRecusadaNaoNotifica() throws Exception {
			// Sexta-feira: dia sem jornada, ordem de chegada.
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "%s",
					  "serviceIds": ["%s"],
					  "scheduledAt": "2026-12-18T10:00:00-03:00",
					  "guestName": "Maria Aparecida",
					  "guestPhone": "5522999990000"
					}
					""".formatted(jhonatan.getId(), corte.getId())))
					.andExpect(status(400));

			verifyNoInteractions(whatsapp);
		}

	}

	// =======================================================================
	// O cancelamento pelo link
	// =======================================================================

	@Nested
	@DisplayName("ao cancelar pelo link")
	class AoCancelar {

		@Test
		@DisplayName("avisa o barbeiro de que o horário ficou livre")
		void avisaOBarbeiro() throws Exception {
			JsonNode criado = agendar(LocalTime.of(14, 30), List.of(corte, barba));
			String token = criado.get("data").get("cancelToken").asString();
			org.mockito.Mockito.reset(whatsapp);
			given(whatsapp.enviar(any())).willReturn(true);

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(200));

			List<ChatMessage> enviadas = capturarEnviadas(1);
			assertThat(enviadas.get(0).telefone()).isEqualTo(TELEFONE_DO_BARBEIRO);
			assertThat(enviadas.get(0).texto())
					.startsWith("❌ *Agendamento cancelado*")
					.contains("👤 *Cliente:* Maria Aparecida")
					.contains("Corte Máquina e Tesoura + Barba Simples")
					.contains("_O horário ficou disponível._");
		}

		@Test
		@DisplayName("falha no aviso não impede o cancelamento")
		void falhaNaoImpedeOCancelamento() throws Exception {
			JsonNode criado = agendar(LocalTime.of(14, 30), List.of(corte));
			String token = criado.get("data").get("cancelToken").asString();
			given(whatsapp.enviar(any())).willThrow(new RuntimeException("fora do ar"));

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(200));

			assertThat(appointments.findByCancelToken(token).orElseThrow().getStatus())
					.isEqualTo(AppointmentStatus.CANCELLED);
			// E o horario voltou a ser vendavel.
			assertThat(horariosLivres()).contains("14:30");
		}

		@Test
		@DisplayName("cancelamento recusado (prazo vencido) NÃO avisa")
		void prazoVencidoNaoAvisa() throws Exception {
			JsonNode criado = agendar(LocalTime.of(14, 30), List.of(corte));
			String token = criado.get("data").get("cancelToken").asString();
            org.mockito.Mockito.reset(whatsapp);
			given(whatsapp.enviar(any())).willReturn(true);

			// 14:00 BRT: faltam 30 minutos.
			BDDMockito.given(clock.instant())
					.willReturn(mapper.toInstantUtc(TERCA, LocalTime.of(14, 0)));

			mvc.perform(postJson("/api/v1/cancel/" + token, "{}")).andExpect(status(400));

			verifyNoInteractions(whatsapp);
		}

	}

	// =======================================================================
	// BARBER_PHONE ausente
	// =======================================================================

	@Nested
	@DisplayName("sem BARBER_PHONE o aviso ao barbeiro é pulado")
	class SemTelefoneDoBarbeiro {

		@Test
		@DisplayName("com BARBER_PHONE configurado, as duas mensagens saem")
		void comTelefoneConfigurado() throws Exception {
			// O contraponto do teste de ausencia, que vive em
			// NotificacaoSemBarberPhoneTest -- um contexto com a propriedade
			// vazia e outro cache do Spring.
			agendar(LocalTime.of(10, 20), List.of(corte));

			assertThat(capturarEnviadas(2)).hasSize(2);
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private List<ChatMessage> capturarEnviadas(int quantidade) {
		ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
		verify(this.whatsapp, org.mockito.Mockito.times(quantidade)).enviar(captor.capture());
		return captor.getAllValues();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
			pedidoDeAgendamento(LocalTime hora, List<Service> servicos) {

		String ids = servicos.stream()
				.map(s -> "\"" + s.getId() + "\"")
				.collect(java.util.stream.Collectors.joining(","));

		return postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": [%s],
				  "scheduledAt": "%sT%s:00-03:00",
				  "guestName": "Maria Aparecida",
				  "guestPhone": "5522999990000"
				}
				""".formatted(jhonatan.getId(), ids, TERCA, hora));
	}

	private JsonNode agendar(LocalTime hora, List<Service> servicos) throws Exception {
		MvcResult criado = mvc.perform(pedidoDeAgendamento(hora, servicos))
				.andExpect(status(201)).andReturn();
		return corpoDe(criado);
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

	private JsonNode corpoDe(MvcResult resultado) throws Exception {
		return this.json.readTree(resultado.getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static org.springframework.test.web.servlet.ResultMatcher status(int esperado) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(esperado);
	}

}
