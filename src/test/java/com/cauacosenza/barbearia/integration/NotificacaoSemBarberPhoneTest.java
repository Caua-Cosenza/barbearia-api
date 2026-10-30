package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.service.ChatMessagePort;
import com.cauacosenza.barbearia.service.ChatMessagePort.ChatMessage;

/**
 * Sem {@code BARBER_PHONE}, o aviso ao barbeiro e pulado — e o do cliente
 * continua saindo.
 *
 * <p>Classe separada porque o Spring cacheia o contexto por conjunto de
 * anotacoes: uma propriedade com valor diferente exige um contexto proprio, e
 * tentar mudar isso no meio de outra classe de teste nao teria efeito.
 *
 * <p><b>Divergencia deliberada em relacao ao Node</b>, registrada no README.
 * O Node faz {@code env.BARBER_PHONE ?? ''} e manda a requisicao de qualquer
 * forma: o telefone vazio vira {@code number: "55"}, que a Evolution recusa.
 * Uma chamada de rede que nao tem como dar certo, e um erro recorrente no log
 * que nao significa nada. Aqui o envio e simplesmente pulado.
 */
@TestPropertySource(properties = {
		"FRONTEND_URL=https://barbeariajhonatan.com",
		// Explicitamente vazia: e o caso de quem nao configurou o WhatsApp do
		// balcao.
		"BARBER_PHONE=",
})
class NotificacaoSemBarberPhoneTest extends AbstractPostgresIntegrationTest {

	private static final LocalDate TERCA = LocalDate.of(2026, 12, 15);

	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	@MockitoBean(name = "clock")
	private Clock clock;

	@MockitoBean
	private ChatMessagePort whatsapp;

	@Autowired
	private MockMvc mvc;

	private Barber jhonatan;

	private Service corte;

	@BeforeEach
	void prepararAgenda() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);
		given(this.whatsapp.enviar(any())).willReturn(true);

		this.jhonatan = novoBarbeiro("Jhonatan Correa");
		this.corte = novoServico("Corte Máquina e Tesoura", 50);

		BarberSchedule terca = new BarberSchedule();
		terca.setProfessional(this.jhonatan);
		terca.setDayOfWeek(2);
		terca.setStartTime("08:40");
		terca.setEndTime("19:30");
		this.schedules.saveAndFlush(terca);
	}

	@Test
	@DisplayName("sai UMA mensagem só, a do cliente")
	void umaMensagemSo() throws Exception {
		agendar(LocalTime.of(10, 20));

		ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
		verify(this.whatsapp, org.mockito.Mockito.times(1)).enviar(captor.capture());

		// Nenhuma tentativa com number: "55", que a Evolution recusaria.
		assertThat(captor.getValue().telefone()).isEqualTo("5522999990000");
		assertThat(captor.getValue().texto()).contains("Seu agendamento foi confirmado");
	}

	@Test
	@DisplayName("o agendamento e a confirmação do cliente seguem normais")
	void agendamentoSegueNormal() throws Exception {
		String id = this.json.readTree(agendar(LocalTime.of(10, 20)))
				.get("data").get("id").asString();

		assertThat(appointments.findById(id)).isPresent();
		// O cliente foi avisado, e a coluna diz isso.
		assertThat(appointments.findById(id).orElseThrow().isConfirmationSent()).isTrue();
	}

	@Test
	@DisplayName("cancelar não tenta avisar ninguém, e o cancelamento funciona")
	void cancelamentoSemAviso() throws Exception {
		String token = this.json.readTree(agendar(LocalTime.of(14, 30)))
				.get("data").get("cancelToken").asString();
		org.mockito.Mockito.reset(this.whatsapp);
		given(this.whatsapp.enviar(any())).willReturn(true);

		mvc.perform(postJson("/api/v1/cancel/" + token, "{}"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.status().is(200));

		// O unico destinatario seria o barbeiro, e nao ha telefone.
		org.mockito.Mockito.verifyNoInteractions(this.whatsapp);
		assertThat(appointments.findByCancelToken(token).orElseThrow().getCancelledAt()).isNotNull();
	}

	private String agendar(LocalTime hora) throws Exception {
		return mvc.perform(postJson("/api/v1/appointments/public", """
				{
				  "professionalId": "%s",
				  "serviceIds": ["%s"],
				  "scheduledAt": "%sT%s:00-03:00",
				  "guestName": "Maria Aparecida",
				  "guestPhone": "5522999990000"
				}
				""".formatted(jhonatan.getId(), corte.getId(), TERCA, hora)))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.status().is(201))
				.andReturn().getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

}
