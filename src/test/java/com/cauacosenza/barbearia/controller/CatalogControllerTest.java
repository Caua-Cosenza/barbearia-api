package com.cauacosenza.barbearia.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.cauacosenza.barbearia.dto.response.AvailabilityResponse;
import com.cauacosenza.barbearia.dto.response.AvailableTimesResponse;
import com.cauacosenza.barbearia.dto.response.ProfessionalResponse;
import com.cauacosenza.barbearia.dto.response.ServiceResponse;

/**
 * As tres rotas de leitura, com os services mockados.
 *
 * <p>O que se verifica aqui e <b>a borda HTTP</b>: formato do envelope, nomes
 * de campo, status, e o que acontece com entrada invalida. A regra de negocio
 * tem os seus proprios testes; mocka-la aqui e o que deixa este teste rapido e
 * focado em contrato.
 *
 * <p>Cada asercao de nome de campo existe porque o frontend le aquele nome. Um
 * {@code amountCents} que virasse {@code amount_cents} compilaria, passaria em
 * todo teste de service, e faria todos os precos da home virarem "Consultar".
 */
class CatalogControllerTest extends AbstractControllerTest {

	@Autowired
	private MockMvc mvc;

	// =======================================================================
	// GET /api/v1/services
	// =======================================================================

	@Nested
	@DisplayName("GET /api/v1/services")
	class Servicos {

		@Test
		@DisplayName("devolve o envelope com a lista e os cinco campos do contrato")
		void envelopeCorreto() throws Exception {
			given(catalogo.listarServicos()).willReturn(List.of(
					new ServiceResponse("11111111-1111-4111-8111-111111111111",
							"Corte Máquina e Tesoura", "Corte masculino", 4500, 50)));

			mvc.perform(getComIpProprio("/api/v1/services"))
					.andExpect(status().isOk())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.error").doesNotExist())
					.andExpect(jsonPath("$.message").value("Success"))
					.andExpect(jsonPath("$.timestamp").exists())
					.andExpect(jsonPath("$.data[0].id").value("11111111-1111-4111-8111-111111111111"))
					.andExpect(jsonPath("$.data[0].name").value("Corte Máquina e Tesoura"))
					.andExpect(jsonPath("$.data[0].description").value("Corte masculino"))
					// O campo que a home usa para formatar o preco.
					.andExpect(jsonPath("$.data[0].amountCents").value(4500))
					.andExpect(jsonPath("$.data[0].durationMinutes").value(50));
		}

		@Test
		@DisplayName("catalogo vazio devolve array vazio, nao null")
		void listaVazia() throws Exception {
			given(catalogo.listarServicos()).willReturn(List.of());

			mvc.perform(getComIpProprio("/api/v1/services"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data").isArray())
					.andExpect(jsonPath("$.data").isEmpty());
		}

		@Test
		@DisplayName("o timestamp sai no formato do toISOString() do JavaScript")
		void timestampNoFormatoDoNode() throws Exception {
			given(catalogo.listarServicos()).willReturn(List.of());

			mvc.perform(getComIpProprio("/api/v1/services"))
					.andExpect(jsonPath("$.timestamp")
							.value(org.hamcrest.Matchers.matchesPattern(
									"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")));
		}

	}

	// =======================================================================
	// GET /api/v1/professionals
	// =======================================================================

	@Nested
	@DisplayName("GET /api/v1/professionals")
	class Profissionais {

		@Test
		@DisplayName("devolve nome decifrado, isWalkIn e a jornada embutida")
		void envelopeCorreto() throws Exception {
			given(catalogo.listarProfissionais()).willReturn(List.of(
					new ProfessionalResponse("00000000-0000-0000-0000-000000000010",
							"Jhonatan Correa", "Barbeiro profissional", false,
							List.of(new AvailabilityResponse(1, "08:40", "19:30")))));

			mvc.perform(getComIpProprio("/api/v1/professionals"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data[0].name").value("Jhonatan Correa"))
					.andExpect(jsonPath("$.data[0].bio").value("Barbeiro profissional"))
					// O frontend usa isWalkIn para bloquear o botao de confirmar.
					.andExpect(jsonPath("$.data[0].isWalkIn").value(false))
					// E availability para desabilitar dias no calendario, com a
					// convencao 0=domingo do JavaScript.
					.andExpect(jsonPath("$.data[0].availability[0].dayOfWeek").value(1))
					.andExpect(jsonPath("$.data[0].availability[0].startTime").value("08:40"))
					.andExpect(jsonPath("$.data[0].availability[0].endTime").value("19:30"));
		}

		@Test
		@DisplayName("profissional de walk-in sai com availability vazia")
		void walkInSemJornada() throws Exception {
			given(catalogo.listarProfissionais()).willReturn(List.of(
					new ProfessionalResponse("00000000-0000-0000-0000-000000000011",
							"Sandro Santos", null, true, List.of())));

			mvc.perform(getComIpProprio("/api/v1/professionals"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data[0].isWalkIn").value(true))
					.andExpect(jsonPath("$.data[0].availability").isArray())
					.andExpect(jsonPath("$.data[0].availability").isEmpty());
		}

	}

	// =======================================================================
	// POST /api/v1/available-times
	// =======================================================================

	@Nested
	@DisplayName("POST /api/v1/available-times")
	class Horarios {

		@Test
		@DisplayName("devolve { slots: [...] } dentro do envelope")
		void envelopeCorreto() throws Exception {
			given(horarios.calcular(any())).willReturn(
					new AvailableTimesResponse(List.of("08:40", "09:30", "10:20")));

			mvc.perform(postJson("/api/v1/available-times", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceId": "11111111-1111-4111-8111-111111111111",
					  "date": "2026-12-15",
					  "totalDurationMinutes": 50
					}
					"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					// O front faz res.data?.slots ?? [] -- precisa ser objeto.
					.andExpect(jsonPath("$.data.slots").isArray())
					.andExpect(jsonPath("$.data.slots[0]").value("08:40"))
					.andExpect(jsonPath("$.data.slots.length()").value(3));
		}

		@Test
		@DisplayName("totalDurationMinutes e opcional")
		void duracaoOpcional() throws Exception {
			given(horarios.calcular(any())).willReturn(new AvailableTimesResponse(List.of()));

			mvc.perform(postJson("/api/v1/available-times", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceId": "11111111-1111-4111-8111-111111111111",
					  "date": "2026-12-15"
					}
					"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true));
		}

		@Test
		@DisplayName("id que nao e UUID devolve 422 no envelope, nao o erro padrao do Spring")
		void idInvalido() throws Exception {
			mvc.perform(postJson("/api/v1/available-times", """
					{
					  "professionalId": "nao-sou-uuid",
					  "serviceId": "11111111-1111-4111-8111-111111111111",
					  "date": "2026-12-15"
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.data").doesNotExist())
					.andExpect(jsonPath("$.error").value("Validation Error"))
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("professionalId")))
					.andExpect(jsonPath("$.timestamp").exists())
					// O formato de erro do Spring traria "path" e "status".
					.andExpect(jsonPath("$.path").doesNotExist())
					.andExpect(jsonPath("$.status").doesNotExist());
		}

		@Test
		@DisplayName("data fora de YYYY-MM-DD devolve 422")
		void dataInvalida() throws Exception {
			mvc.perform(postJson("/api/v1/available-times", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceId": "11111111-1111-4111-8111-111111111111",
					  "date": "15/12/2026"
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.error").value("Validation Error"))
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("date")));
		}

		@Test
		@DisplayName("duracao negativa devolve 422")
		void duracaoNegativa() throws Exception {
			mvc.perform(postJson("/api/v1/available-times", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceId": "11111111-1111-4111-8111-111111111111",
					  "date": "2026-12-15",
					  "totalDurationMinutes": -10
					}
					"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.message").value(
							org.hamcrest.Matchers.containsString("totalDurationMinutes")));
		}

		@Test
		@DisplayName("propriedade desconhecida e rejeitada — o equivalente do .strict() do Zod")
		void propriedadeDesconhecida() throws Exception {
			mvc.perform(postJson("/api/v1/available-times", """
					{
					  "professionalId": "00000000-0000-0000-0000-000000000010",
					  "serviceId": "11111111-1111-4111-8111-111111111111",
					  "date": "2026-12-15",
					  "campoQueNaoExiste": "x"
					}
					"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.error").value("Bad Request"))
					// A mensagem do Jackson citaria a classe Java e as
					// propriedades conhecidas do DTO.
					.andExpect(jsonPath("$.message").value("Corpo da requisição inválido"));
		}

		@Test
		@DisplayName("JSON malformado devolve 400 no envelope")
		void jsonMalformado() throws Exception {
			mvc.perform(post("/api/v1/available-times")
					.with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{ isso nao e json"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.error").value("Bad Request"));
		}

	}

}
