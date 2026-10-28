package com.cauacosenza.barbearia.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.cauacosenza.barbearia.domain.exception.ForaDoExpedienteException;
import com.cauacosenza.barbearia.domain.exception.HorarioReservadoException;
import com.cauacosenza.barbearia.domain.exception.PrazoCancelamentoException;
import com.cauacosenza.barbearia.domain.exception.ProfissionalIndisponivelException;
import com.cauacosenza.barbearia.domain.exception.RecursoNaoEncontradoException;
import com.cauacosenza.barbearia.domain.exception.SlotIndisponivelException;
import com.cauacosenza.barbearia.dto.response.CancelPageResponse;
import com.cauacosenza.barbearia.dto.response.ProfessionalRefResponse;
import com.cauacosenza.barbearia.dto.response.PublicAppointmentResponse;
import com.cauacosenza.barbearia.dto.response.ServiceSnapshotResponse;

/**
 * A borda HTTP da criacao e do cancelamento de agendamento.
 *
 * <p>Alem do formato do envelope, aqui se verifica <b>o mapeamento de cada
 * excecao de dominio para o seu status</b>. Esse mapeamento e o contrato de
 * erro: trocar um 409 por 500 nao quebra nenhum teste de service, mas muda o
 * que o cliente ve de "esse horario acabou de ser reservado" para "erro
 * inesperado".
 */
class AppointmentControllerTest extends AbstractControllerTest {

	private static final String TOKEN = "22222222-2222-4222-8222-222222222222";

	private static final String CORPO_VALIDO = """
			{
			  "professionalId": "00000000-0000-0000-0000-000000000010",
			  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
			  "scheduledAt": "2099-12-15T10:00:00-03:00",
			  "guestName": "Maria Aparecida",
			  "guestPhone": "5522999990000"
			}
			""";

	@Autowired
	private MockMvc mvc;

	// =======================================================================
	// POST /api/v1/appointments/public
	// =======================================================================

	@Nested
	@DisplayName("POST /api/v1/appointments/public")
	class Criacao {

		@Test
		@DisplayName("devolve 201 com o envelope e todos os campos do contrato")
		void criaComSucesso() throws Exception {
			given(agendamentos.criar(any())).willReturn(resposta());

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("Appointment created"))
					.andExpect(jsonPath("$.data.id").value("33333333-3333-4333-8333-333333333333"))
					// O token do link de cancelamento, que vai por WhatsApp.
					.andExpect(jsonPath("$.data.cancelToken").value(TOKEN))
					.andExpect(jsonPath("$.data.status").value("PENDING"))
					.andExpect(jsonPath("$.data.services[0].serviceId")
							.value("11111111-1111-4111-8111-111111111111"))
					.andExpect(jsonPath("$.data.services[0].name").value("Corte"))
					.andExpect(jsonPath("$.data.services[0].amountCents").value(4500))
					.andExpect(jsonPath("$.data.services[0].durationMinutes").value(50))
					.andExpect(jsonPath("$.data.totalAmountCents").value(4500))
					.andExpect(jsonPath("$.data.totalDurationMinutes").value(50))
					.andExpect(jsonPath("$.data.professional.name").value("Jhonatan Correa"));
		}

		@Test
		@DisplayName("scheduledAt sai no formato do toISOString() do JavaScript")
		void scheduledAtNoFormatoDoNode() throws Exception {
			given(agendamentos.criar(any())).willReturn(resposta());

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(jsonPath("$.data.scheduledAt").value("2099-12-15T13:00:00.000Z"));
		}

		@Test
		@DisplayName("telefone com formatacao e rejeitado: o contrato exige so digitos")
		void telefoneComMascara() throws Exception {
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
					  "scheduledAt": "2099-12-15T10:00:00-03:00",
					  "guestName": "Maria",
					  "guestPhone": "(22) 99999-0000"
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.error").value("Validation Error"))
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("guestPhone")));
		}

		@Test
		@DisplayName("nome com menos de 2 caracteres devolve 422")
		void nomeMuitoCurto() throws Exception {
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
					  "scheduledAt": "2099-12-15T10:00:00-03:00",
					  "guestName": "M",
					  "guestPhone": "5522999990000"
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("guestName")));
		}

		@Test
		@DisplayName("lista de servicos vazia devolve 422")
		void semServico() throws Exception {
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceIds": [],
					  "scheduledAt": "2099-12-15T10:00:00-03:00",
					  "guestName": "Maria",
					  "guestPhone": "5522999990000"
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("Selecione ao menos um serviço")));
		}

		@Test
		@DisplayName("scheduledAt SEM fuso e rejeitado — e o que a tela morta Booking.tsx mandaria")
		void semFusoHorario() throws Exception {
			// z.string().datetime({offset:true}) tambem recusa. Confirmado
			// rodando o proprio Zod do projeto Node.
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
					  "scheduledAt": "2099-12-15T10:00:00",
					  "guestName": "Maria",
					  "guestPhone": "5522999990000"
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("scheduledAt")));
		}

		@Test
		@DisplayName("data no passado devolve 422 pela validacao, nao 400 pelo service")
		void dataNoPassado() throws Exception {
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
					  "scheduledAt": "2020-01-15T10:00:00-03:00",
					  "guestName": "Maria",
					  "guestPhone": "5522999990000"
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.error").value("Validation Error"))
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("Data deve ser no futuro")));
		}

	}

	// =======================================================================
	// Preco no corpo
	// =======================================================================

	@Nested
	@DisplayName("valor no corpo e recusado antes de qualquer coisa")
	class PrecoNoCorpo {

		@org.junit.jupiter.params.ParameterizedTest(name = "campo \"{0}\"")
		@org.junit.jupiter.params.provider.ValueSource(strings = { "amount", "price", "value" })
		@DisplayName("400 com a mensagem do Node")
		void camposDePrecoSaoRecusados(String campo) throws Exception {
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
					  "scheduledAt": "2099-12-15T10:00:00-03:00",
					  "guestName": "Maria",
					  "guestPhone": "5522999990000",
					  "%s": 1
					}
					""".formatted(campo)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.error").value("Bad Request"))
					.andExpect(jsonPath("$.message").value("Invalid request payload"));
		}

		@Test
		@DisplayName("outro campo desconhecido cai no strict do Jackson, tambem com 400")
		void outroCampoDesconhecido() throws Exception {
			mvc.perform(postJson("/api/v1/appointments/public", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
					  "scheduledAt": "2099-12-15T10:00:00-03:00",
					  "guestName": "Maria",
					  "guestPhone": "5522999990000",
					  "amountCents": 1
					}
					"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Corpo da requisição inválido"));
		}

	}

	// =======================================================================
	// Excecao de dominio -> status
	// =======================================================================

	@Nested
	@DisplayName("cada excecao de dominio vira o seu status, com mensagem de negocio")
	class MapeamentoDeErro {

		@Test
		@DisplayName("SlotIndisponivel -> 409, nunca 500 — as duas faces da corrida perdida")
		void slotIndisponivel() throws Exception {
			given(agendamentos.criar(any())).willThrow(new SlotIndisponivelException());

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.error").value("Conflict"))
					.andExpect(jsonPath("$.message")
							.value("Este horário acabou de ser reservado. Escolha outro."));
		}

		@Test
		@DisplayName("a causa do deadlock nao vaza para a resposta")
		void deadlockNaoVaza() throws Exception {
			// O perdedor da corrida pode chegar aqui carregando a excecao do
			// Postgres. A mensagem dela cita SQL e nomes de coluna.
			given(agendamentos.criar(any())).willThrow(new SlotIndisponivelException(
					new org.springframework.dao.CannotAcquireLockException(
							"deadlock detected while checking exclusion constraint on tuple (1,1)")));

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.message")
							.value("Este horário acabou de ser reservado. Escolha outro."))
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("deadlock"))));
		}

		@Test
		@DisplayName("ProfissionalIndisponivel -> 400")
		void profissionalSemJornada() throws Exception {
			given(agendamentos.criar(any())).willThrow(new ProfissionalIndisponivelException());

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Profissional não atende neste dia"));
		}

		@Test
		@DisplayName("ForaDoExpediente -> 400")
		void foraDoExpediente() throws Exception {
			given(agendamentos.criar(any()))
					.willThrow(ForaDoExpedienteException.inicioForaDaJornada());

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Horário fora do expediente"));
		}

		@Test
		@DisplayName("HorarioReservado -> 409")
		void bloqueioDoProfissional() throws Exception {
			given(agendamentos.criar(any())).willThrow(new HorarioReservadoException());

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.message")
							.value("Este horário está reservado pelo profissional"));
		}

		@Test
		@DisplayName("RecursoNaoEncontrado -> 404")
		void servicoInexistente() throws Exception {
			given(agendamentos.criar(any())).willThrow(
					RecursoNaoEncontradoException.servico("11111111-1111-4111-8111-111111111111"));

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error").value("Not Found"));
		}

		@Test
		@DisplayName("excecao inesperada -> 500 generico, sem nada da stack")
		void inesperada() throws Exception {
			given(agendamentos.criar(any()))
					.willThrow(new IllegalStateException("SELECT * FROM users WHERE cpf = '123'"));

			mvc.perform(postJson("/api/v1/appointments/public", CORPO_VALIDO))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.error").value("Internal Server Error"))
					.andExpect(jsonPath("$.message").value("Erro inesperado"))
					// O detalhe da excecao pode conter SQL e PII.
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT"))));
		}

	}

	// =======================================================================
	// Cancelamento
	// =======================================================================

	@Nested
	@DisplayName("GET e POST /api/v1/cancel/{token}")
	class Cancelamento {

		@Test
		@DisplayName("GET devolve os dados da tela com canCancel")
		void consultaOk() throws Exception {
			given(cancelamentos.consultar(TOKEN)).willReturn(new CancelPageResponse(
					"33333333-3333-4333-8333-333333333333",
					"Maria Aparecida",
					Instant.parse("2099-12-15T13:00:00Z"),
					"PENDING",
					List.of(new CancelPageResponse.ItemResponse("Corte", 4500, 50)),
					4500, 50, true));

			mvc.perform(getComIpProprio("/api/v1/cancel/" + TOKEN))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data.guestName").value("Maria Aparecida"))
					.andExpect(jsonPath("$.data.scheduledAt").value("2099-12-15T13:00:00.000Z"))
					.andExpect(jsonPath("$.data.services[0].name").value("Corte"))
					.andExpect(jsonPath("$.data.services[0].amountCents").value(4500))
					.andExpect(jsonPath("$.data.totalAmountCents").value(4500))
					// O booleano que habilita o botao da tela.
					.andExpect(jsonPath("$.data.canCancel").value(true));
		}

		@Test
		@DisplayName("POST devolve success com data nula")
		void cancelaOk() throws Exception {
			doNothing().when(cancelamentos).cancelar(TOKEN);

			mvc.perform(postJson("/api/v1/cancel/" + TOKEN, "{}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data").doesNotExist())
					.andExpect(jsonPath("$.message").value("Agendamento cancelado com sucesso"));
		}

		@Test
		@DisplayName("token inexistente -> 404")
		void tokenInexistente() throws Exception {
			given(cancelamentos.consultar(TOKEN))
					.willThrow(RecursoNaoEncontradoException.agendamento());

			mvc.perform(getComIpProprio("/api/v1/cancel/" + TOKEN))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error").value("Not Found"))
					.andExpect(jsonPath("$.message").value("Agendamento não encontrado"));
		}

		@Test
		@DisplayName("token malformado -> 422, sem chegar ao service")
		void tokenMalformado() throws Exception {
			mvc.perform(getComIpProprio("/api/v1/cancel/nao-sou-uuid"))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.error").value("Validation Error"))
					// Com o nome do parametro na frente, como o ZodError do
					// Node monta `${path}: ${message}`.
					.andExpect(jsonPath("$.message").value("token: Token inválido"));
		}

		@Test
		@DisplayName("prazo encerrado -> 400 com a mensagem do Node")
		void prazoEncerrado() throws Exception {
			willThrow(PrazoCancelamentoException.prazoEncerrado())
					.given(cancelamentos).cancelar(TOKEN);

			mvc.perform(postJson("/api/v1/cancel/" + TOKEN, "{}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(
							"Prazo de cancelamento encerrado. Entre em contato com a barbearia."));
		}

		@Test
		@DisplayName("segundo cancelamento -> 400 de negocio, nao 500")
		void segundoCancelamento() throws Exception {
			willThrow(PrazoCancelamentoException.statusNaoCancelavel())
					.given(cancelamentos).cancelar(TOKEN);

			mvc.perform(postJson("/api/v1/cancel/" + TOKEN, "{}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value("Agendamento não pode ser cancelado"));
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private static PublicAppointmentResponse resposta() {
		return new PublicAppointmentResponse(
				"33333333-3333-4333-8333-333333333333",
				TOKEN,
				Instant.parse("2099-12-15T13:00:00Z"),
				"PENDING",
				null,
				List.of(new ServiceSnapshotResponse(
						"11111111-1111-4111-8111-111111111111", "Corte", 4500, 50)),
				4500, 50,
				new ProfessionalRefResponse(
						"00000000-0000-0000-0000-000000000010", "Jhonatan Correa"));
	}

}
