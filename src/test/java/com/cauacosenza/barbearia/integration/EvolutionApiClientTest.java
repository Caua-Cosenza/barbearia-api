package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.cauacosenza.barbearia.service.ChatMessagePort;
import com.cauacosenza.barbearia.service.ChatMessagePort.ChatMessage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O adaptador da Evolution contra um servidor HTTP de verdade.
 *
 * <p><b>Por que o {@code HttpServer} do JDK e nao WireMock.</b> Nenhuma
 * dependencia nova: o {@code jdk.httpserver} ja vem com a JVM, entao o CI nao
 * baixa nada a mais e o build de quem clonar o repositorio nao fica mais
 * lento. E, para o que se precisa aqui — ler o corpo e os cabecalhos que
 * chegaram, devolver um status escolhido, e <b>demorar de proposito</b> para
 * provocar timeout — a API do JDK basta e deixa o teste mais explicito do que
 * um DSL de stub.
 *
 * <p>Nenhuma requisicao sai para a Evolution de verdade: o servidor sobe em
 * {@code 127.0.0.1} numa porta efemera.
 */
class EvolutionApiClientTest {

	private static final String API_KEY = "chave-ficticia-de-teste";

	private static final String INSTANCIA = "barbearia";

	private static final ObjectMapper JSON = new ObjectMapper();

	private HttpServer servidor;

	/** O que o servidor recebeu, em ordem. */
	private final List<Requisicao> recebidas = new CopyOnWriteArrayList<>();

	/** Status que o servidor vai devolver na proxima requisicao. */
	private volatile int statusDeResposta = 200;

	/** Quanto o servidor vai demorar antes de responder. */
	private volatile Duration atraso = Duration.ZERO;

	private ChatMessagePort adaptador;

	@BeforeEach
	void subirServidor() throws IOException {
		this.servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.servidor.createContext("/", this::atender);
		this.servidor.start();

		String url = "http://127.0.0.1:" + this.servidor.getAddress().getPort();
		this.adaptador = new EvolutionApiClient(clienteCom(url), INSTANCIA, API_KEY);
	}

	@AfterEach
	void derrubarServidor() {
		this.servidor.stop(0);
	}

	// =======================================================================
	// O formato da requisição
	// =======================================================================

	@Nested
	@DisplayName("a requisição que chega à Evolution")
	class Formato {

		@Test
		@DisplayName("POST /message/sendText/{instancia}")
		void caminho() {
			adaptador.enviar(new ChatMessage("5522999990000", "oi"));

			assertThat(recebidas).hasSize(1);
			assertThat(recebidas.get(0).metodo()).isEqualTo("POST");
			assertThat(recebidas.get(0).caminho()).isEqualTo("/message/sendText/barbearia");
		}

		@Test
		@DisplayName("a apikey vai no cabeçalho 'apikey', não em Authorization")
		void cabecalhoDaApiKey() {
			adaptador.enviar(new ChatMessage("5522999990000", "oi"));

			Requisicao pedido = recebidas.get(0);
			// A Evolution espera o cabecalho 'apikey' minusculo. Authorization
			// seria ignorado e a mensagem recusada com 401.
			assertThat(pedido.cabecalho("apikey")).isEqualTo(API_KEY);
			assertThat(pedido.cabecalho("authorization")).isNull();
			assertThat(pedido.cabecalho("content-type")).contains("application/json");
		}

		@Test
		@DisplayName("o corpo tem exatamente dois campos: number e text")
		void corpo() {
			adaptador.enviar(new ChatMessage("5522999990000", "Olá, *Maria*! 🎉"));

			JsonNode corpo = recebidas.get(0).corpoJson();
			assertThat(corpo.propertyNames()).containsExactlyInAnyOrder("number", "text");
			assertThat(corpo.get("number").asString()).isEqualTo("5522999990000");
			// O emoji atravessa como UTF-8, nao como escape nem interrogacao.
			assertThat(corpo.get("text").asString()).isEqualTo("Olá, *Maria*! 🎉");
		}

		@Test
		@DisplayName("texto com quebras de linha e asteriscos chega intacto")
		void textoMultilinha() {
			String original = "🔔 *Novo agendamento!*\n\n👤 *Cliente:* Maria\n💰 *Total:* R$ 60,00";

			adaptador.enviar(new ChatMessage("5522999990000", original));

			assertThat(recebidas.get(0).corpoJson().get("text").asString()).isEqualTo(original);
		}

	}

	// =======================================================================
	// Normalização do telefone
	// =======================================================================

	@Nested
	@DisplayName("o DDI 55 é acrescentado quando falta")
	class Telefone {

		@ParameterizedTest(name = "\"{0}\" chega como \"{1}\"")
		@CsvSource({
				// Com 55: passa intacto.
				"5522999990000, 5522999990000",
				// Sem 55: o prefixo entra. Tem cliente salvo de cada jeito.
				"22999990000, 5522999990000",
				// Com mascara: os nao-digitos somem antes.
				"'(22) 99999-0000', 5522999990000",
				"+55 22 99999-0000, 5522999990000",
				"'22 99999-0000', 5522999990000",
		})
		void normaliza(String entrada, String esperado) {
			adaptador.enviar(new ChatMessage(entrada, "oi"));

			assertThat(recebidas.get(0).corpoJson().get("number").asString()).isEqualTo(esperado);
		}

		@Test
		@DisplayName("com e sem 55 chegam IGUAIS ao destino")
		void comESemDdiSaoEquivalentes() {
			adaptador.enviar(new ChatMessage("5522999990000", "oi"));
			adaptador.enviar(new ChatMessage("22999990000", "oi"));

			assertThat(recebidas.get(0).corpoJson().get("number").asString())
					.isEqualTo(recebidas.get(1).corpoJson().get("number").asString());
		}

	}

	// =======================================================================
	// Resiliência — o ponto da fase
	// =======================================================================

	@Nested
	@DisplayName("falha nunca sobe, e o resultado diz a verdade")
	class Resiliencia {

		@Test
		@DisplayName("200 devolve true — é o que autoriza marcar confirmation_sent")
		void sucesso() {
			statusDeResposta = 200;

			assertThat(adaptador.enviar(new ChatMessage("5522999990000", "oi"))).isTrue();
		}

		@ParameterizedTest(name = "HTTP {0} devolve false, sem lançar")
		@ValueSource(ints = { 400, 401, 403, 404, 429, 500, 502, 503 })
		void erroHttp(int status) {
			statusDeResposta = status;

			// O Node nao olha a resposta -- um 401 por chave errada passa como
			// sucesso, em silencio. Aqui o status entra na decisao, senao
			// confirmation_sent mentiria.
			boolean enviado = adaptador.enviar(new ChatMessage("5522999990000", "oi"));

			assertThat(enviado).isFalse();
			assertThat(recebidas).hasSize(1);
		}

		@Test
		@DisplayName("500 não lança para cima")
		void quinhentosNaoLanca() {
			statusDeResposta = 500;

			assertThatCode(() -> adaptador.enviar(new ChatMessage("5522999990000", "oi")))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("timeout devolve false, sem lançar")
		void timeout() {
			// O servidor demora 3s; o cliente desiste em 300ms. Sem timeout
			// explicito o default do HttpClient do JDK e INFINITO para
			// leitura, e a thread ficaria presa.
			atraso = Duration.ofSeconds(3);
			ChatMessagePort impaciente = new EvolutionApiClient(
					clienteCom("http://127.0.0.1:" + servidor.getAddress().getPort(),
							Duration.ofMillis(300)),
					INSTANCIA, API_KEY);

			long comecou = System.nanoTime();
			boolean enviado = impaciente.enviar(new ChatMessage("5522999990000", "oi"));
			Duration levou = Duration.ofNanos(System.nanoTime() - comecou);

			assertThat(enviado).isFalse();
			// Desistiu antes de o servidor responder: o timeout valeu.
			assertThat(levou).isLessThan(Duration.ofSeconds(2));
		}

		@Test
		@DisplayName("servidor inalcançável devolve false, sem lançar")
		void servidorFora() {
			// Porta onde nao ha ninguem: conexao recusada.
			ChatMessagePort semDestino = new EvolutionApiClient(
					clienteCom("http://127.0.0.1:1"), INSTANCIA, API_KEY);

			assertThat(semDestino.enviar(new ChatMessage("5522999990000", "oi"))).isFalse();
		}

		@Test
		@DisplayName("host inexistente devolve false, sem lançar")
		void dnsQuebrado() {
			ChatMessagePort semDns = new EvolutionApiClient(
					clienteCom("http://evolution-que-nao-existe.invalid"), INSTANCIA, API_KEY);

			assertThat(semDns.enviar(new ChatMessage("5522999990000", "oi"))).isFalse();
		}

	}

	// =======================================================================
	// O NoOp
	// =======================================================================

	@Nested
	@DisplayName("Evolution não configurada: o NoOp assume")
	class NoOp {

		private final ChatMessagePort noOp = new NoOpChatMessageAdapter();

		@Test
		@DisplayName("não faz nada e não lança")
		void naoFazNada() {
			assertThatCode(() -> noOp.enviar(new ChatMessage("5522999990000", "oi")))
					.doesNotThrowAnyException();

			assertThat(recebidas).as("nenhuma requisicao HTTP").isEmpty();
		}

		@Test
		@DisplayName("devolve false: nada foi enviado, então confirmation_sent não é marcado")
		void devolveFalse() {
			// Devolver true deixaria no banco o registro de uma mensagem que
			// nunca existiu, e o reenvio manual pularia justamente quem ficou
			// sem aviso.
			assertThat(noOp.enviar(new ChatMessage("5522999990000", "oi"))).isFalse();
		}

	}

	// =======================================================================
	// Mascaramento
	// =======================================================================

	@Nested
	@DisplayName("mascaramento do telefone para o log")
	class Mascaramento {

		@ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
		@CsvSource({
				"5522999990000, 5522****0000",
				"5511987654321, 5511****4321",
				"5522992718402, 5522****8402",
		})
		void mascara(String numero, String esperado) {
			// DDI + DDD na frente e quatro ultimos digitos: da para reconhecer
			// de qual cliente se trata, e nao da para ligar para ele.
			assertThat(EvolutionApiClient.mascarar(numero)).isEqualTo(esperado);
		}

		@Test
		@DisplayName("número curto ou nulo vira **** inteiro")
		void curtoOuNulo() {
			assertThat(EvolutionApiClient.mascarar("5522")).isEqualTo("****");
			assertThat(EvolutionApiClient.mascarar(null)).isEqualTo("****");
		}

		@Test
		@DisplayName("o número completo nunca sobra no resultado")
		void naoVazaOCompleto() {
			String completo = "5522999990000";

			assertThat(EvolutionApiClient.mascarar(completo))
					.doesNotContain(completo)
					// O miolo tem que ter sumido.
					.doesNotContain("99999");
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private static RestClient clienteCom(String url) {
		return clienteCom(url, Duration.ofSeconds(5));
	}

	private static RestClient clienteCom(String url, Duration timeoutDeLeitura) {
		HttpClient jdk = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();

		JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(jdk);
		fabrica.setReadTimeout(timeoutDeLeitura);

		return RestClient.builder().baseUrl(url).requestFactory(fabrica).build();
	}

	private void atender(HttpExchange troca) throws IOException {
		byte[] corpo = troca.getRequestBody().readAllBytes();

		List<String> cabecalhos = new ArrayList<>();
		troca.getRequestHeaders().forEach((nome, valores) ->
				cabecalhos.add(nome.toLowerCase() + "=" + String.join(",", valores)));

		this.recebidas.add(new Requisicao(
				troca.getRequestMethod(),
				troca.getRequestURI().getPath(),
				cabecalhos,
				new String(corpo, StandardCharsets.UTF_8)));

		if (!this.atraso.isZero()) {
			try {
				Thread.sleep(this.atraso.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		byte[] resposta = "{}".getBytes(StandardCharsets.UTF_8);
		troca.getResponseHeaders().add("Content-Type", "application/json");
		troca.sendResponseHeaders(this.statusDeResposta, resposta.length);
		try (var saida = troca.getResponseBody()) {
			saida.write(resposta);
		}
	}

	/** Uma requisicao que o servidor recebeu. */
	private record Requisicao(String metodo, String caminho, List<String> cabecalhos, String corpo) {

		String cabecalho(String nome) {
			return this.cabecalhos.stream()
					.filter(c -> c.startsWith(nome.toLowerCase() + "="))
					.map(c -> c.substring(nome.length() + 1))
					.findFirst()
					.orElse(null);
		}

		JsonNode corpoJson() {
			return JSON.readTree(this.corpo);
		}

	}

}
