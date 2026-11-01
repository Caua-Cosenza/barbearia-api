package com.cauacosenza.barbearia.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import com.cauacosenza.barbearia.service.ChatMessagePort;
import com.cauacosenza.barbearia.service.MensagensDeWhatsApp;

/**
 * Fala HTTP com a Evolution API para entregar WhatsApp.
 *
 * <p>Adaptador do {@link ChatMessagePort}. Todo o conhecimento de Evolution —
 * caminho, cabecalho de autenticacao, formato do corpo — esta confinado aqui.
 *
 * <pre>
 * POST {EVOLUTION_API_URL}/message/sendText/{EVOLUTION_INSTANCE_NAME}
 * Content-Type: application/json
 * apikey: {EVOLUTION_API_KEY}
 *
 * { "number": "5522999990000", "text": "..." }
 * </pre>
 *
 * <p>O cabecalho e {@code apikey} em minusculas e <b>nao</b>
 * {@code Authorization}: e o que a Evolution espera, e o que o Node envia.
 *
 * <h2>Nunca lanca</h2>
 *
 * <p>Contrato do port, e a razao de existir desta fase: WhatsApp fora do ar
 * nao pode impedir um agendamento. Erro de rede, timeout, 500 da Evolution,
 * DNS quebrado — tudo vira {@code false}. O {@code catch (Exception)} e
 * amplo de proposito: qualquer coisa que escape daqui viraria 500 numa
 * requisicao que deu certo.
 *
 * <h2>Status HTTP e verificado — ao contrario do Node</h2>
 *
 * <p>O Node faz {@code await fetch(...)} e <b>nao olha a resposta</b>. O
 * {@code fetch} so rejeita em falha de rede, entao um {@code 401} por chave
 * errada ou um {@code 500} da Evolution passam como sucesso, em silencio
 * absoluto — nem log. Aqui o status entra na decisao, e e ele que define se
 * {@code confirmation_sent} vai ser marcado. Divergencia deliberada: sem isso
 * a coluna mentiria.
 *
 * <h2>O que pode e o que nao pode ir para o log</h2>
 *
 * <p>Este projeto ja teve um incidente de log vazando conteudo de conversa de
 * WhatsApp. As regras, sem excecao:
 *
 * <table border="1">
 *   <caption>Regras de log</caption>
 *   <tr><th>Nunca</th><th>Pode</th></tr>
 *   <tr><td>o corpo da mensagem</td><td>o id do agendamento</td></tr>
 *   <tr><td>o telefone completo</td><td>sucesso ou falha</td></tr>
 *   <tr><td>a resposta bruta da Evolution</td><td>o codigo de status HTTP</td></tr>
 *   <tr><td>a {@code EVOLUTION_API_KEY}, nem truncada</td><td>o telefone mascarado</td></tr>
 * </table>
 *
 * <p>Por isso o {@code catch} loga {@code ex.getClass().getSimpleName()} e
 * <b>nao</b> {@code ex.getMessage()}: a mensagem de uma excecao de HTTP
 * costuma trazer a URL, e a URL da Evolution pode carregar a instancia; a de
 * uma excecao de serializacao pode trazer o corpo. E por isso que a excecao
 * nao vai como {@code cause} — o stack trace imprimiria os dois.
 */
public class EvolutionApiClient implements ChatMessagePort {

	private static final Logger log = LoggerFactory.getLogger(EvolutionApiClient.class);

	/** O cabecalho que a Evolution usa — minusculo, e nao Authorization. */
	private static final String CABECALHO_API_KEY = "apikey";

	/**
	 * Serializa o corpo <b>antes</b> do envio, para que o tamanho seja
	 * conhecido.
	 *
	 * <h2>Por que nao deixar o RestClient serializar</h2>
	 *
	 * <p>Passando o record direto em {@code body(...)}, o converter do Jackson
	 * escreve no {@code OutputStream} da requisicao sem saber o tamanho final,
	 * e o {@code JdkClientHttpRequest} do Spring so informa o tamanho ao
	 * {@code BodyPublisher} quando {@code HttpHeaders.getContentLength()} ja e
	 * maior que zero — caso contrario chama
	 * {@code BodyPublishers.fromPublisher(publisher)} sem comprimento, e o JDK
	 * manda {@code Transfer-Encoding: chunked}. Nao e um
	 * {@code BodyPublishers.ofString} que esqueceu de medir: nao ha
	 * {@code ofString} nenhum no caminho.
	 *
	 * <p>Com {@code byte[]} o {@code ByteArrayHttpMessageConverter} devolve
	 * {@code bytes.length} em {@code getContentLength}, o header entra, e o
	 * publisher sai com comprimento — {@code Content-Length}, como o Node.
	 *
	 * <p><b>{@code byte[]} e nao {@code String} de proposito.</b> O
	 * {@code StringHttpMessageConverter} tem charset default, e o
	 * {@code addDefaultHeaders} o acrescentaria ao {@code Content-Type},
	 * trocando {@code application/json} por
	 * {@code application/json;charset=UTF-8} — divergencia nova no lugar da
	 * que se quer fechar. O converter de {@code byte[]} nao tem charset
	 * default e deixa o {@code Content-Type} como foi pedido.
	 *
	 * <p>Mapper proprio, e nao o da aplicacao: o corpo e um record de dois
	 * campos {@code String}, para o qual todo mapper produz os mesmos bytes, e
	 * assim nenhum customizador global futuro muda, sem querer, o que a
	 * Evolution recebe.
	 */
	private static final ObjectMapper JSON = new ObjectMapper();

	private final RestClient http;

	private final String instancia;

	private final String apiKey;

	public EvolutionApiClient(RestClient http, String instancia, String apiKey) {
		this.http = http;
		this.instancia = instancia;
		this.apiKey = apiKey;
	}

	@Override
	public boolean enviar(ChatMessage mensagem) {
		String numero = comDdi(mensagem.telefone());

		try {
			ResponseEntity<Void> resposta = this.http.post()
					.uri("/message/sendText/{instancia}", this.instancia)
					.contentType(MediaType.APPLICATION_JSON)
					.header(CABECALHO_API_KEY, this.apiKey)
					.body(JSON.writeValueAsBytes(new EnvioDeTexto(numero, mensagem.texto())))
					// toBodilessEntity e nao body(String.class): a resposta da
					// Evolution ecoa a mensagem enviada, e nao ha motivo para
					// trazer isso para a memoria da aplicacao.
					.retrieve()
					// Sem isto o RestClient lanca em 4xx e 5xx; aqui o status
					// e dado, nao excecao.
					.onStatus(status -> true, (pedido, falha) -> {
					})
					.toBodilessEntity();

			boolean sucesso = resposta.getStatusCode().is2xxSuccessful();
			if (sucesso) {
				log.info("WhatsApp enviado para {} (HTTP {})",
						mascarar(numero), resposta.getStatusCode().value());
			}
			else {
				// O corpo do erro da Evolution pode ecoar a mensagem: fica de
				// fora. Status e telefone mascarado bastam para diagnosticar.
				log.warn("Evolution recusou o envio para {} (HTTP {})",
						mascarar(numero), resposta.getStatusCode().value());
			}
			return sucesso;
		}
		catch (Exception ex) {
			// Timeout, DNS, conexao recusada, TLS. Apenas o NOME da excecao:
			// a mensagem dela traria a URL, e a URL traz a instancia.
			log.warn("Falha ao enviar WhatsApp para {}: {}",
					mascarar(numero), ex.getClass().getSimpleName());
			return false;
		}
	}

	/**
	 * Acrescenta o DDI 55 quando ele nao esta la.
	 *
	 * <p>{@code digits.startsWith('55') ? digits : '55' + digits}, igual ao
	 * Node. Nao e firula: tem cliente cujo numero foi salvo com o 55 e cliente
	 * cujo numero nao foi, e a Evolution precisa do formato internacional.
	 *
	 * <p>Note que isto <b>nao</b> e o mesmo que
	 * {@link MensagensDeWhatsApp#formatarTelefone(String)}, que prepara o
	 * numero para <i>leitura humana</i> na mensagem do barbeiro e usa uma
	 * condicao diferente. As duas regras convivem no Node desde sempre.
	 */
	private static String comDdi(String telefone) {
		String digitos = MensagensDeWhatsApp.somenteDigitos(telefone);
		return digitos.startsWith("55") ? digitos : "55" + digitos;
	}

	/**
	 * {@code 5522999990000} para {@code 5522****0000}.
	 *
	 * <p>Mantem o DDI com o DDD e os quatro ultimos digitos: o suficiente para
	 * reconhecer de qual cliente se trata ao investigar, e insuficiente para
	 * ligar para ele a partir do log. Numero curto demais para mascarar com
	 * seguranca vira {@code ****}.
	 */
	static String mascarar(String numero) {
		if (numero == null || numero.length() < 8) {
			return "****";
		}
		return numero.substring(0, 4) + "****" + numero.substring(numero.length() - 4);
	}

	/**
	 * O corpo que a Evolution espera.
	 *
	 * <p>Exatamente dois campos, com estes nomes. Um record para que o Jackson
	 * produza {@code {"number":...,"text":...}} e nao exista a tentacao de
	 * acrescentar metadado que a Evolution ignoraria.
	 */
	record EnvioDeTexto(String number, String text) {

	}

}
