package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.slf4j.LoggerFactory;

/**
 * Captura tudo que a integracao de WhatsApp escreve no log e procura por
 * vazamento.
 *
 * <p><b>Este projeto ja teve um incidente de log vazando conteudo de conversa
 * de WhatsApp.</b> Um teste que le o log de verdade e a unica forma de impedir
 * que volte — uma regra escrita em comentario nao sobrevive ao proximo
 * {@code log.debug} acrescentado as pressas para depurar.
 *
 * <p>O teste cobre os quatro caminhos que escrevem log: sucesso, recusa da
 * Evolution, falha de rede, e o NoOp. Em todos, nada de:
 *
 * <ul>
 *   <li>o corpo da mensagem (nem um pedaco);
 *   <li>o telefone completo — so mascarado;
 *   <li>a resposta bruta da Evolution;
 *   <li>a {@code EVOLUTION_API_KEY}, nem truncada.
 * </ul>
 *
 * <p>Nao sobe contexto Spring: o {@code ListAppender} do Logback e anexado
 * direto aos loggers das duas classes, e o adaptador e instanciado a mao.
 */
class LogsNaoVazamPiiTest {

	/** Chave FICTICIA, longa e distintiva para ser facil de procurar no log. */
	private static final String API_KEY = "chave-secretissima-da-evolution-abc123xyz";

	private static final String INSTANCIA = "instancia-secreta-da-barbearia";

	private static final String TELEFONE = "5522999990000";

	/** Texto com dados reconheciveis: nome, valor, link e token. */
	private static final String TEXTO = MensagensDeWhatsApp.confirmacaoAoCliente(
			"Maria Aparecida da Conceicao",
			java.time.Instant.parse("2026-12-15T13:20:00Z"),
			List.of("Corte Máquina e Tesoura", "Barba Simples"),
			10000,
			MensagensDeWhatsApp.linkDeCancelamento(
					"https://barbeariajhonatan.com", "11111111-1111-4111-8111-111111111111"));

	private HttpServer servidor;

	private int statusDeResposta = 200;

	private ListAppender<ILoggingEvent> capturados;

	private Logger loggerDoCliente;

	private Logger loggerDoNoOp;

	@BeforeEach
	void prepararCapturaDeLog() throws IOException {
		this.servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.servidor.createContext("/", troca -> {
			troca.getRequestBody().readAllBytes();
			// A Evolution ecoa a mensagem na resposta -- exatamente o tipo de
			// corpo que nao pode acabar no log.
			byte[] corpo = ("{\"key\":{\"id\":\"ABC\"},\"message\":{\"conversation\":\""
					+ TEXTO.replace("\n", " ").replace("\"", "'")
					+ "\"}}").getBytes(StandardCharsets.UTF_8);
			troca.sendResponseHeaders(this.statusDeResposta, corpo.length);
			try (var saida = troca.getResponseBody()) {
				saida.write(corpo);
			}
		});
		this.servidor.start();

		this.capturados = new ListAppender<>();
		this.capturados.start();

		this.loggerDoCliente = (Logger) LoggerFactory.getLogger(EvolutionApiClient.class);
		this.loggerDoNoOp = (Logger) LoggerFactory.getLogger(NoOpChatMessageAdapter.class);
		// DEBUG de proposito: o teste tem que ver TUDO que a classe escreve,
		// inclusive o que normalmente nao apareceria em producao.
		this.loggerDoCliente.setLevel(Level.TRACE);
		this.loggerDoNoOp.setLevel(Level.TRACE);
		this.loggerDoCliente.addAppender(this.capturados);
		this.loggerDoNoOp.addAppender(this.capturados);
	}

	@AfterEach
	void limpar() {
		this.loggerDoCliente.detachAppender(this.capturados);
		this.loggerDoNoOp.detachAppender(this.capturados);
		this.capturados.stop();
		this.servidor.stop(0);
	}

	// =======================================================================

	@Test
	@DisplayName("envio com sucesso não vaza nada")
	void sucesso() {
		this.statusDeResposta = 200;

		boolean enviado = adaptador().enviar(new ChatMessage(TELEFONE, TEXTO));

		assertThat(enviado).isTrue();
		assertThat(log()).isNotEmpty();
		verificarQueNadaVazou();
	}

	@Test
	@DisplayName("recusa da Evolution (500) não vaza nada — nem a resposta bruta")
	void recusa() {
		this.statusDeResposta = 500;

		adaptador().enviar(new ChatMessage(TELEFONE, TEXTO));

		assertThat(log()).isNotEmpty();
		verificarQueNadaVazou();
	}

	@Test
	@DisplayName("401 por chave errada não imprime a chave")
	void chaveErrada() {
		this.statusDeResposta = 401;

		adaptador().enviar(new ChatMessage(TELEFONE, TEXTO));

		assertThat(log()).contains("401");
		verificarQueNadaVazou();
	}

	@Test
	@DisplayName("falha de rede não vaza a URL nem a instância")
	void falhaDeRede() {
		// Host inexistente: a mensagem da excecao traria a URL, e a URL traz a
		// instancia -- por isso so o NOME da excecao vai para o log.
		ChatMessagePort semDns = new EvolutionApiClient(
				clienteCom("http://evolution-que-nao-existe.invalid"), INSTANCIA, API_KEY);

		semDns.enviar(new ChatMessage(TELEFONE, TEXTO));

		assertThat(log()).isNotEmpty();
		verificarQueNadaVazou();
		// A instancia faz parte do caminho da URL e nao pode aparecer.
		assertThat(log()).doesNotContain(INSTANCIA);
	}

	@Test
	@DisplayName("timeout não vaza nada")
	void timeout() throws IOException {
		HttpServer lento = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		lento.createContext("/", troca -> {
			try {
				Thread.sleep(3000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		lento.start();
		try {
			ChatMessagePort impaciente = new EvolutionApiClient(
					clienteCom("http://127.0.0.1:" + lento.getAddress().getPort(),
							Duration.ofMillis(200)),
					INSTANCIA, API_KEY);

			assertThat(impaciente.enviar(new ChatMessage(TELEFONE, TEXTO))).isFalse();
			verificarQueNadaVazou();
		}
		finally {
			lento.stop(0);
		}
	}

	@Test
	@DisplayName("o NoOp não loga nem o telefone mascarado")
	void noOp() {
		new NoOpChatMessageAdapter().enviar(new ChatMessage(TELEFONE, TEXTO));

		// Nao houve envio: nao ha o que diagnosticar, e portanto nada de
		// telefone, nem mascarado.
		verificarQueNadaVazou();
		assertThat(log()).doesNotContain("5522");
	}

	@Test
	@DisplayName("o telefone aparece MASCARADO, e só")
	void telefoneMascarado() {
		this.statusDeResposta = 200;

		adaptador().enviar(new ChatMessage(TELEFONE, TEXTO));

		// O mascarado pode: e o que permite reconhecer o cliente ao
		// investigar, sem permitir ligar para ele.
		assertThat(log()).contains("5522****0000");
		assertThat(log()).doesNotContain(TELEFONE);
	}

	// =======================================================================
	// A verificação
	// =======================================================================

	private void verificarQueNadaVazou() {
		String registrado = log();

		assertThat(registrado)
				.as("a EVOLUTION_API_KEY nao pode aparecer, nem truncada")
				.doesNotContain(API_KEY)
				.doesNotContain(API_KEY.substring(0, 12));

		assertThat(registrado)
				.as("o telefone completo nao pode aparecer")
				.doesNotContain(TELEFONE);

		assertThat(registrado)
				.as("nenhum pedaco do corpo da mensagem pode aparecer")
				.doesNotContain("Maria Aparecida")
				.doesNotContain("Corte Máquina")
				.doesNotContain("Seu agendamento foi confirmado")
				.doesNotContain("barbeariajhonatan.com")
				.doesNotContain("11111111-1111-4111-8111-111111111111")
				.doesNotContain("R$ 100,00");

		assertThat(registrado)
				.as("a resposta bruta da Evolution nao pode aparecer")
				.doesNotContain("conversation")
				.doesNotContain("{\"key\"");

		// Varredura final: nenhuma linha longa de digitos que possa ser um
		// telefone inteiro. Pega o caso de alguem logar o numero por um
		// caminho que este teste nao previu.
		assertThat(registrado.replaceAll("[^0-9]", " "))
				.as("nenhuma sequencia de 11+ digitos no log")
				.doesNotMatch(".*\\d{11,}.*");
	}

	private String log() {
		return this.capturados.list.stream()
				// getFormattedMessage inclui os argumentos ja substituidos; o
				// stack trace entra a parte, se houver.
				.map(evento -> evento.getFormattedMessage() + " "
						+ (evento.getThrowableProxy() != null
								? evento.getThrowableProxy().getClassName() + " "
										+ evento.getThrowableProxy().getMessage()
								: ""))
				.collect(Collectors.joining("\n"));
	}

	private ChatMessagePort adaptador() {
		return new EvolutionApiClient(
				clienteCom("http://127.0.0.1:" + this.servidor.getAddress().getPort()),
				INSTANCIA, API_KEY);
	}

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

}
