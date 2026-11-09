package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * <p>As requisicoes se apresentam como vindas da borda ({@code X-Edge-Token} +
 * {@code CF-Connecting-IP}), que e o unico jeito de escolher o IP visto pelo
 * {@code ClientIpResolver}. Qual cabecalho vira a chave em cada cenario e
 * assunto de {@link ResolucaoDeIpDoClienteTest}; aqui o que se mede sao os
 * limites.
 *
 * <p>Nao ha teste de "o balde se recompoe depois de um minuto": seria um
 * minuto de espera real por asercao. A reposicao e configuracao do Bucket4j
 * ({@code refillIntervally}), nao logica desta aplicacao.
 */
class RateLimitTest extends AbstractPostgresIntegrationTest {

	/** O balde global do {@code index.ts} do Node. */
	private static final int LIMITE_GLOBAL = 30;

	/** O {@code bookingRateLimit} do Node. */
	private static final int LIMITE_AGENDAMENTO = 10;

	@Autowired
	private MockMvc mvc;

	@Test
	@DisplayName("criacao de agendamento: o 11.o pedido do mesmo IP leva 429")
	void baldeDeAgendamento() throws Exception {
		String ip = RequisicaoDaBorda.proximoIp();

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
		String ip = RequisicaoDaBorda.proximoIp();

		for (int i = 1; i <= LIMITE_GLOBAL; i++) {
			mvc.perform(catalogoDe(ip))
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isOk());
		}

		mvc.perform(catalogoDe(ip))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.status().isTooManyRequests());
	}

	@Test
	@DisplayName("os baldes sao por rota: estourar o de agendamento nao fecha o catalogo")
	void baldesIndependentes() throws Exception {
		String ip = RequisicaoDaBorda.proximoIp();

		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoDe(ip));
		}
		// O balde de agendamento esta estourado.
		assertThat(mvc.perform(agendamentoDe(ip)).andReturn().getResponse().getStatus())
				.isEqualTo(429);

		// O global, do mesmo IP, continua intacto.
		assertThat(mvc.perform(catalogoDe(ip)).andReturn().getResponse().getStatus())
				.isEqualTo(200);
	}

	@Test
	@DisplayName("IPs diferentes tem baldes diferentes — e o que o Node NAO faz")
	void baldePorIp() throws Exception {
		String saturado = RequisicaoDaBorda.proximoIp();
		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoDe(saturado));
		}
		assertThat(mvc.perform(agendamentoDe(saturado)).andReturn().getResponse().getStatus())
				.isEqualTo(429);

		// Outro cliente nao paga pelo primeiro. No Node isto falharia: o
		// Fastify nao tem trustProxy ligado, entao req.ip e o endereco do
		// proxy do Railway e todos os clientes dividem um unico balde.
		assertThat(mvc.perform(agendamentoDe(RequisicaoDaBorda.proximoIp()))
				.andReturn().getResponse().getStatus()).isNotEqualTo(429);
	}

	@Test
	@DisplayName("sem o token da borda, a chave vem da ultima entrada do X-Forwarded-For")
	void usaForwardedForQuandoNaoHaTokenDaBorda() throws Exception {
		String cliente = RequisicaoDaBorda.proximoIp();
		// "proxy1, proxy2, cliente": a ultima entrada e quem abriu a conexao
		// com o proxy mais proximo, e e a unica que o cliente nao escreveu.
		String cabecalho = "10.0.0.1, 10.0.0.2, " + cliente;

		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoComForwardedFor(cabecalho));
		}
		assertThat(mvc.perform(agendamentoComForwardedFor(cabecalho))
				.andReturn().getResponse().getStatus()).isEqualTo(429);

		// O mesmo par, atras de outra lista de proxies, cai no mesmo balde.
		assertThat(mvc.perform(agendamentoComForwardedFor("10.9.9.9, " + cliente))
				.andReturn().getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	@DisplayName("o token da borda tem precedencia sobre o X-Forwarded-For")
	void bordaVenceForwardedFor() throws Exception {
		String daBorda = RequisicaoDaBorda.proximoIp();

		for (int i = 0; i <= LIMITE_AGENDAMENTO; i++) {
			mvc.perform(agendamentoDe(daBorda)
					.header("X-Forwarded-For", RequisicaoDaBorda.proximoIp()));
		}

		// Trocar so o X-Forwarded-For nao escapa do balde: com o token
		// conferindo, nenhum outro cabecalho e consultado.
		assertThat(mvc.perform(agendamentoDe(daBorda)
				.header("X-Forwarded-For", RequisicaoDaBorda.proximoIp()))
				.andReturn().getResponse().getStatus()).isEqualTo(429);
	}

	@Test
	@DisplayName("/actuator/health fica fora do limite")
	void healthNaoEhLimitado() throws Exception {
		String ip = RequisicaoDaBorda.proximoIp();
		// O healthcheck do container bate a cada poucos segundos, sempre do
		// mesmo IP: limita-lo derrubaria o container que ele deveria vigiar.
		for (int i = 0; i < LIMITE_GLOBAL + 5; i++) {
			mvc.perform(RequisicaoDaBorda.comIp(get("/actuator/health"), ip))
					.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isOk());
		}
	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private static MockHttpServletRequestBuilder catalogoDe(String ip) {
		return RequisicaoDaBorda.comIp(get("/api/v1/services"), ip);
	}

	private static MockHttpServletRequestBuilder agendamentoDe(String ip) {
		return RequisicaoDaBorda.comIp(agendamento(), ip);
	}

	/** Um agendamento que NAO se apresenta como vindo da borda. */
	private static MockHttpServletRequestBuilder agendamentoComForwardedFor(String valor) {
		return agendamento().header("X-Forwarded-For", valor);
	}

	private static MockHttpServletRequestBuilder agendamento() {
		return post("/api/v1/appointments/public")
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

}
