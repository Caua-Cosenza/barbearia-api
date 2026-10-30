package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.cauacosenza.barbearia.service.ChatMessagePort;
import com.cauacosenza.barbearia.service.ChatMessagePort.ChatMessage;
import com.cauacosenza.barbearia.service.MensagensDeWhatsApp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A requisicao HTTP que o Java monta, comparada <b>campo a campo</b> com a que
 * o Node monta.
 *
 * <p>O fixture {@code whatsapp-fixture-node.json} tem as requisicoes reais do
 * {@code whatsappService.ts}, capturadas com o {@code fetch} global
 * interceptado. Aqui o adaptador Java manda para um {@code HttpServer} local, e
 * as duas requisicoes sao comparadas: caminho, metodo, cabecalhos, chaves do
 * corpo, {@code number} e {@code text}.
 *
 * <p><b>Nenhuma requisicao sai para a Evolution.</b> Nem a de teste (servidor
 * em {@code 127.0.0.1}) nem a que gerou o fixture (o {@code fetch} foi
 * substituido).
 *
 * <p>Este e o mesmo metodo das Fases 5 e 6 — subir os dois lados e comparar
 * campo a campo — com a diferenca de que aqui a comparacao fica <b>dentro da
 * suite</b>, e portanto roda no CI para sempre, em vez de ser um script que
 * alguem precisa lembrar de executar.
 */
class EquivalenciaComONodeTest {

	/** A mesma chave ficticia que gerou o fixture. */
	private static final String API_KEY = "chave-ficticia-de-teste";

	/** A mesma instancia do fixture — o caminho da URL depende dela. */
	private static final String INSTANCIA = "barbearia";

	private static final ObjectMapper JSON = new ObjectMapper();

	/** 2026-11-03 10:30 em Brasilia, o instante do fixture. */
	private static final Instant QUANDO = Instant.parse("2026-11-03T13:30:00Z");

	private static final List<String> SERVICOS =
			List.of("Corte Máquina e Tesoura", "Barba Simples");

	private static final JsonNode FIXTURE = carregarFixture();

	private HttpServer servidor;

	private final List<Capturada> doJava = new CopyOnWriteArrayList<>();

	private ChatMessagePort adaptador;

	@BeforeEach
	void subir() throws IOException {
		this.servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.servidor.createContext("/", troca -> {
			byte[] corpo = troca.getRequestBody().readAllBytes();

			List<String> cabecalhos = new ArrayList<>();
			troca.getRequestHeaders().forEach((nome, valores) ->
					cabecalhos.add(nome.toLowerCase() + "=" + String.join(",", valores)));

			this.doJava.add(new Capturada(
					troca.getRequestMethod(),
					troca.getRequestURI().getPath(),
					cabecalhos,
					new String(corpo, StandardCharsets.UTF_8)));

			troca.sendResponseHeaders(200, 2);
			try (var saida = troca.getResponseBody()) {
				saida.write("{}".getBytes(StandardCharsets.UTF_8));
			}
		});
		this.servidor.start();

		HttpClient jdk = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(jdk);
		fabrica.setReadTimeout(Duration.ofSeconds(5));

		this.adaptador = new EvolutionApiClient(
				RestClient.builder()
						.baseUrl("http://127.0.0.1:" + this.servidor.getAddress().getPort())
						.requestFactory(fabrica)
						.build(),
				INSTANCIA, API_KEY);
	}

	@AfterEach
	void derrubar() {
		this.servidor.stop(0);
	}

	// =======================================================================

	@Test
	@DisplayName("confirmação ao cliente: requisição idêntica à do Node")
	void confirmacaoAoCliente() {
		String texto = MensagensDeWhatsApp.confirmacaoAoCliente(
				"Maria Aparecida", QUANDO, SERVICOS, 6000,
				MensagensDeWhatsApp.linkDeCancelamento(
						"https://barbeariajhonatan.com",
						"11111111-1111-4111-8111-111111111111"));

		this.adaptador.enviar(new ChatMessage("5522999990000", texto));

		compararComFixture(0);
	}

	@Test
	@DisplayName("novo agendamento ao barbeiro: requisição idêntica à do Node")
	void novoAgendamentoAoBarbeiro() {
		String texto = MensagensDeWhatsApp.novoAgendamentoAoBarbeiro(
				"Maria Aparecida", "5522999990000", QUANDO, SERVICOS, 6000);

		this.adaptador.enviar(new ChatMessage("5522992718402", texto));

		compararComFixture(1);
	}

	@Test
	@DisplayName("cancelamento ao barbeiro: requisição idêntica à do Node")
	void cancelamentoAoBarbeiro() {
		String texto = MensagensDeWhatsApp.cancelamentoAoBarbeiro(
				"Maria Aparecida", QUANDO, SERVICOS);

		this.adaptador.enviar(new ChatMessage("5522992718402", texto));

		compararComFixture(2);
	}

	@Test
	@DisplayName("telefone SEM o 55: o number enviado é idêntico ao do Node")
	void telefoneSemDdi() {
		String texto = MensagensDeWhatsApp.cancelamentoAoBarbeiro(
				"Teste Sem 55", QUANDO, List.of("Sobrancelha"));

		// O fixture [3] foi gerado com '22992718402', sem o 55. O Node
		// acrescentou o prefixo antes de enviar; o Java tem que fazer igual.
		this.adaptador.enviar(new ChatMessage("22992718402", texto));

		compararComFixture(3);
	}

	@Test
	@DisplayName("telefone fixo de 10 dígitos: requisição idêntica à do Node")
	void telefoneFixo() {
		String texto = MensagensDeWhatsApp.novoAgendamentoAoBarbeiro(
				"Cliente Fixo", "2233334444", QUANDO, List.of("Sobrancelha"), 700);

		this.adaptador.enviar(new ChatMessage("5522992718402", texto));

		compararComFixture(4);
	}

	@Test
	@DisplayName("o caminho da URL usa a instância, como no Node")
	void caminhoDaUrl() {
		this.adaptador.enviar(new ChatMessage("5522999990000", "oi"));

		String caminhoDoNode = URI.create(
				FIXTURE.get("capturas").get(0).get("url").asString()).getPath();

		assertThat(this.doJava.get(0).caminho()).isEqualTo(caminhoDoNode);
		assertThat(caminhoDoNode).isEqualTo("/message/sendText/" + INSTANCIA);
	}

	// =======================================================================
	// A comparação
	// =======================================================================

	/**
	 * Compara a unica requisicao que o Java mandou com a captura indicada do
	 * fixture: metodo, caminho, cabecalhos e corpo.
	 */
	private void compararComFixture(int indice) {
		assertThat(this.doJava).as("o Java mandou exatamente uma requisicao").hasSize(1);

		Capturada java = this.doJava.get(0);
		JsonNode node = FIXTURE.get("capturas").get(indice);
		JsonNode corpoDoNode = node.get("body");
		JsonNode corpoDoJava = JSON.readTree(java.corpo());

		// --- metodo e caminho ---
		assertThat(java.metodo()).isEqualTo(node.get("method").asString());
		assertThat(java.caminho())
				.isEqualTo(URI.create(node.get("url").asString()).getPath());

		// --- cabecalhos ---
		assertThat(java.cabecalho("content-type"))
				.as("Content-Type")
				.contains("application/json");
		assertThat(java.cabecalho("apikey"))
				.as("a Evolution espera 'apikey', nao Authorization")
				.isEqualTo(node.get("headers").get("apikey").asString());

		// --- corpo: as mesmas chaves, e so elas ---
		assertThat(corpoDoJava.propertyNames())
				.as("o corpo tem exatamente os campos do Node")
				.containsExactlyInAnyOrderElementsOf(
						() -> corpoDoNode.propertyNames().iterator());

		// --- corpo: os valores ---
		assertThat(corpoDoJava.get("number").asString())
				.as("number")
				.isEqualTo(corpoDoNode.get("number").asString());

		// A comparacao que mais importa: o texto que o cliente le, inteiro.
		assertThat(corpoDoJava.get("text").asString())
				.as("text")
				.isEqualTo(corpoDoNode.get("text").asString());
	}

	private record Capturada(String metodo, String caminho, List<String> cabecalhos, String corpo) {

		String cabecalho(String nome) {
			return this.cabecalhos.stream()
					.filter(c -> c.startsWith(nome.toLowerCase() + "="))
					.map(c -> c.substring(nome.length() + 1))
					.findFirst()
					.orElse(null);
		}

	}

	private static JsonNode carregarFixture() {
		try (InputStream in = EquivalenciaComONodeTest.class
				.getResourceAsStream("/whatsapp-fixture-node.json")) {

			if (in == null) {
				throw new IllegalStateException("fixture /whatsapp-fixture-node.json nao encontrada");
			}
			return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
