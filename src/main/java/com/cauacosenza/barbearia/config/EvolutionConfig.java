package com.cauacosenza.barbearia.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.cauacosenza.barbearia.integration.EvolutionApiClient;
import com.cauacosenza.barbearia.integration.NoOpChatMessageAdapter;
import com.cauacosenza.barbearia.service.ChatMessagePort;

/**
 * Decide, no boot, qual {@link ChatMessagePort} a aplicacao vai usar.
 *
 * <p>Com as tres variaveis da Evolution presentes, entra o
 * {@link EvolutionApiClient}. Faltando qualquer uma, entra o
 * {@link NoOpChatMessageAdapter} e o sistema sobe sem WhatsApp. E a mesma
 * checagem do {@code sendMessage} do Node, so que feita <b>uma vez</b>, no
 * boot, em vez de a cada mensagem — e o log sai uma vez em vez de um aviso
 * por agendamento.
 *
 * <h2>Timeouts explicitos</h2>
 *
 * <p>Os defaults do {@link HttpClient} do JDK sao <b>infinitos</b> para
 * leitura. Sem timeout, uma Evolution que aceita a conexao e nunca responde
 * segura a thread para sempre — e, como o envio acontece depois do commit,
 * seriam threads do Tomcat acumulando sem que nenhuma transacao travasse,
 * o que torna o sintoma dificil de ler: o banco esta bem, o site nao responde.
 *
 * <p>Cinco segundos para leitura e tres para conexao: WhatsApp e aviso, nao
 * parte da reserva. Se a Evolution nao respondeu em cinco segundos, desistir
 * e melhor do que esperar — a mensagem pode ser reenviada depois a partir de
 * {@code confirmation_sent}.
 */
@Configuration
public class EvolutionConfig {

	private static final Logger log = LoggerFactory.getLogger(EvolutionConfig.class);

	private static final Duration TIMEOUT_DE_CONEXAO = Duration.ofSeconds(3);

	private static final Duration TIMEOUT_DE_LEITURA = Duration.ofSeconds(5);

	@Bean
	ChatMessagePort chatMessagePort(
			@Value("${EVOLUTION_API_URL:}") String url,
			@Value("${EVOLUTION_API_KEY:}") String apiKey,
			@Value("${EVOLUTION_INSTANCE_NAME:}") String instancia) {

		if (!StringUtils.hasText(url) || !StringUtils.hasText(apiKey)
				|| !StringUtils.hasText(instancia)) {

			// Quais faltam NAO entra no log: dizer "EVOLUTION_API_KEY ausente"
			// e inofensivo, mas a lista convida a logar os valores na proxima
			// vez que alguem for depurar isto.
			log.info("Evolution API nao configurada — mensagens de WhatsApp desativadas");
			return new NoOpChatMessageAdapter();
		}

		log.info("Evolution API configurada — mensagens de WhatsApp ativas");
		return new EvolutionApiClient(restClientDaEvolution(url), instancia, apiKey);
	}

	/**
	 * O {@link RestClient} da Evolution.
	 *
	 * <p>Nao e um bean: e proprio desta integracao, e expo-lo convidaria outro
	 * componente a reusar um cliente com {@code baseUrl} da Evolution.
	 *
	 * <p>A {@code apiKey} <b>nao</b> entra como {@code defaultHeader}. Vai
	 * requisicao a requisicao, no {@link EvolutionApiClient}. A diferenca
	 * importa para o log: um cabecalho default fica guardado no objeto do
	 * cliente, e qualquer {@code toString()} ou dump de bean em um
	 * {@code /actuator/beans} mal exposto o imprimiria.
	 */
	public static RestClient restClientDaEvolution(String url) {
		return restClientDaEvolution(url, TIMEOUT_DE_LEITURA);
	}

	/**
	 * O mesmo cliente, com o timeout de leitura escolhido.
	 *
	 * <p><b>Publico de proposito.</b> O {@code EvolutionApiClientTest}
	 * construia o seu proprio {@link RestClient} com uma copia desta
	 * configuracao, e copia de configuracao de transporte e armadilha: a
	 * divergencia de {@code Transfer-Encoding} e de {@code Upgrade: h2c} que
	 * motivou este metodo passaria verde no teste e iria para producao do
	 * mesmo jeito. O teste agora chama isto.
	 */
	public static RestClient restClientDaEvolution(String url, Duration timeoutDeLeitura) {
		HttpClient jdk = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT_DE_CONEXAO)
				// HTTP/1.1 explicito. O default do HttpClient e HTTP_2, e sobre
				// texto claro isso nao fala HTTP/2: fala HTTP/1.1 com
				// `Upgrade: h2c` e `HTTP2-Settings` em toda requisicao,
				// pedindo promocao. O Node nao manda nada disso, e a tentativa
				// de upgrade tem efeito observado: contra um WireMock sem
				// `--disable-http2-plain` o corpo chegava VAZIO. Nao e
				// hipotese sobre a Evolution, e um servidor real que engasgou.
				.version(HttpClient.Version.HTTP_1_1)
				// Nunca seguir redirecionamento: um 301 de um host comprometido
				// levaria a mensagem (e a apikey no cabecalho) para outro
				// destino.
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();

		JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(jdk);
		fabrica.setReadTimeout(timeoutDeLeitura);

		return RestClient.builder()
				.baseUrl(url)
				.requestFactory(fabrica)
				// `Accept: */*` e o que o fetch do Node manda. Ausente seria
				// equivalente por RFC — sem preferencia significa qualquer
				// tipo —, mas o criterio desta migracao e igualdade
				// verificada, nao equivalencia presumida: o header e
				// replicavel, entao e replicado. O que NAO se replica e o
				// `Accept: application/json` que pareceria mais correto aqui;
				// ele afastaria do Node em vez de aproximar.
				.defaultHeader("Accept", "*/*")
				.build();
	}

}
