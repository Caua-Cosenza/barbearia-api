package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;

/**
 * O rate limit, com os limites do backend Node.
 *
 * <p>Cada teste usa uma chave de IP propria, porque os baldes vivem no Redis e
 * sao compartilhados entre testes — sem isolar, a ordem de execucao mudaria o
 * resultado.
 *
 * <p>Nao ha teste de "o balde se recompoe depois de um minuto": seria um
 * minuto de espera real por asercao. A reposicao e configuracao do Bucket4j
 * ({@code refillIntervally}), nao logica desta aplicacao.
 */
class RateLimitTest extends AbstractPostgresIntegrationTest {

	private static final String CF_CONNECTING_IP = "CF-Connecting-IP";

	/** O balde global do {@code index.ts} do Node. */
	private static final int LIMITE_GLOBAL = 30;

	/** O {@code bookingRateLimit} do Node. */
	private static final int LIMITE_AGENDAMENTO = 10;

	@Autowired
	private MockMvc mvc;

	@Test
	@DisplayName("criacao de agendamento: o 11.o pedido do mesmo IP leva 429")
	void baldeDeAgendamento() throws Exception {
		String ip = ipDeTeste();

		// Os 10 primeiros passam pelo filtro. Falham depois, por falta de
		// dados -- o que nao importa: o que se mede aqui e quem passou do
		// filtro de rate limit, e qualquer status que nao seja 429 passou.
		for (int i = 1; i <= LIMITE_AGENDAMENTO; i++) {
			int status = mvc.perform(agendamentoDe(ip)).andReturn().getResponse().getStatus();
			assertThat(status).as("pedido %d nao deveria ser limitado", i).isNotEqualTo(429);
		}

		var excedente = mvc.perform(agendamentoDe(ip)).andReturn().getResponse();
		assertThat(excedente.getStatus()).isEqualTo(429);

		// O 429 sai no envelope, nao na pagina de erro do Spring: o client.ts
		// do frontend espera success/data/error/message em toda resposta.
		JsonNode corpo = this.json.readTree(
				excedente.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(corpo.get("success").asBoolean()).isFalse();
		assertThat(corpo.get("data").isNull()).isTrue();
		assertThat(corpo.get("error").asString()).isEqualTo("Too many requests");
		assertThat(corpo.get("message").asString())
				.isEqualTo("Rate limit exceeded. Try again in 1 minute.");
		assertThat(corpo.get("timestamp").asString())
				.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
	}

	@Test
	@DisplayName("catalogo: o 31.o pedido do mesmo IP leva 429")
	void baldeGlobal() throws Exception {
		String ip = ipDeTeste();

		for (int i = 1; i <= LIMITE_GLOBAL; i++) {
			mvc.perform(get("/api/v1/services").header(CF_CONNECTING_IP, ip))
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isOk());
		}

		mvc.perform(get("/api/v1/services").header(CF_CONNECTING_IP, ip))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.status().isTooManyRequests());
	}

	@Test
	@DisplayName("os baldes sao por rota: estourar o de agendamento nao fecha o catalogo")
	void baldesIndependentes() throws Exception {
		String ip = ipDeTeste();

		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoDe(ip));
		}
		// O balde de agendamento esta estourado.
		assertThat(mvc.perform(agendamentoDe(ip)).andReturn().getResponse().getStatus())
				.isEqualTo(429);

		// O global, do mesmo IP, continua intacto.
		assertThat(mvc.perform(get("/api/v1/services").header(CF_CONNECTING_IP, ip))
				.andReturn().getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("IPs diferentes tem baldes diferentes — e o que o Node NAO faz")
	void baldePorIp() throws Exception {
		String saturado = ipDeTeste();
		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoDe(saturado));
		}
		assertThat(mvc.perform(agendamentoDe(saturado)).andReturn().getResponse().getStatus())
				.isEqualTo(429);

		// Outro cliente nao paga pelo primeiro. No Node isto falharia: o
		// Fastify nao tem trustProxy ligado, entao req.ip e o endereco do
		// proxy do Railway e todos os clientes dividem um unico balde.
		assertThat(mvc.perform(agendamentoDe(ipDeTeste())).andReturn().getResponse().getStatus())
				.isNotEqualTo(429);
	}

	@Test
	@DisplayName("X-Forwarded-For serve de chave quando nao ha CF-Connecting-IP")
	void usaForwardedForComoSegundaOpcao() throws Exception {
		String cliente = ipDeTeste();
		// "cliente, proxy1, proxy2": a origem e a primeira entrada.
		String cabecalho = cliente + ", 10.0.0.1, 10.0.0.2";

		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoCom("X-Forwarded-For", cabecalho));
		}
		assertThat(mvc.perform(agendamentoCom("X-Forwarded-For", cabecalho))
				.andReturn().getResponse().getStatus()).isEqualTo(429);

		// O mesmo cliente por tras de outros proxies cai no mesmo balde.
		assertThat(mvc.perform(agendamentoCom("X-Forwarded-For", cliente + ", 10.9.9.9"))
				.andReturn().getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	@DisplayName("CF-Connecting-IP tem precedencia sobre X-Forwarded-For")
	void cloudflareVenceForwardedFor() throws Exception {
		String cloudflare = ipDeTeste();
		String forjado = ipDeTeste();

		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoDe(cloudflare).header("X-Forwarded-For", forjado));
		}

		// Trocar so o X-Forwarded-For nao escapa do balde: o Cloudflare
		// sobrescreve o CF-Connecting-IP e e nele que se confia primeiro.
		assertThat(mvc.perform(agendamentoDe(cloudflare).header("X-Forwarded-For", ipDeTeste()))
				.andReturn().getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	@DisplayName("/actuator/health fica fora do limite")
	void healthNaoEhLimitado() throws Exception {
		String ip = ipDeTeste();
		// O healthcheck do container bate a cada poucos segundos, sempre do
		// mesmo IP: limita-lo derrubaria o container que ele deveria vigiar.
		for (int i = 0; i < LIMITE_GLOBAL + 5; i++) {
			mvc.perform(get("/actuator/health").header(CF_CONNECTING_IP, ip))
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isOk());
		}
	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private MockHttpServletRequestBuilder agendamentoDe(String ip) {
		return agendamentoCom(CF_CONNECTING_IP, ip);
	}

	private MockHttpServletRequestBuilder agendamentoCom(String cabecalho, String valor) {
		return post("/api/v1/appointments/public")
				.header(cabecalho, valor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "professionalId": "00000000-0000-0000-0000-000000000010",
						  "serviceIds": ["11111111-1111-4111-8111-111111111111"],
						  "scheduledAt": "2099-12-15T10:00:00-03:00",
						  "guestName": "Maria",
						  "guestPhone": "5522999990000"
						}
						""");
	}

	/** Chave unica por teste: os baldes vivem no Redis e sobrevivem a suite. */
	private static String ipDeTeste() {
		return "teste-" + UUID.randomUUID();
	}

}
