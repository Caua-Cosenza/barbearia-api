package com.cauacosenza.barbearia.config;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cauacosenza.barbearia.dto.response.ApiResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * Rate limit por IP, com os mesmos limites do backend Node.
 *
 * <table border="1">
 *   <caption>Limites por minuto</caption>
 *   <tr><th>Rota</th><th>Limite</th><th>Origem no Node</th></tr>
 *   <tr><td>{@code POST /api/v1/appointments/**}</td><td>10</td>
 *       <td>{@code bookingRateLimit}</td></tr>
 *   <tr><td>{@code POST /api/v1/available-times}</td><td>30</td>
 *       <td>{@code availableTimesRateLimit}</td></tr>
 *   <tr><td>{@code POST /api/v1/auth/**}</td><td>5</td>
 *       <td>{@code authRateLimit}</td></tr>
 *   <tr><td>todo o resto</td><td>30</td>
 *       <td>o {@code max: 30} global do {@code index.ts}</td></tr>
 * </table>
 *
 * <p>O balde de 5 por minuto em {@code /auth} e o mais apertado de todos, e e
 * o unico cujo motivo e ataque e nao capacidade: ele limita a forca bruta de
 * senha. Cinco tentativas por minuto por IP tornam inviavel varrer um
 * dicionario, sem atrapalhar quem errou a propria senha duas ou tres vezes.
 * Note que isso cobre tambem o {@code /refresh}: um painel aberto renova no
 * maximo uma vez a cada 15 minutos, entao a folga e enorme.
 *
 * <p>O limite de 30 no {@code available-times} nao e o global por acaso: ele
 * foi ajustado depois que clientes trocando de data rapido no modal tomavam
 * 429. Como o modal dispara uma chamada por troca de data (com 300ms de
 * debounce), uma pessoa indecisa gasta o balde sozinha — e por isso que a rota
 * tem o valor mais alto e nao o mais baixo.
 *
 * <p><b>Os baldes sao por rota, nao compartilhados.</b> A chave inclui o nome
 * do balde, entao gastar os 10 da criacao de agendamento nao consome os 30 da
 * consulta de horarios. E o comportamento do {@code @fastify/rate-limit}, onde
 * a configuracao de rota substitui a global.
 *
 * <h2>O 429 sai no envelope</h2>
 *
 * <p>Escrito a mao na resposta, porque um filtro roda <b>antes</b> do
 * {@code DispatcherServlet} e portanto fora do alcance do
 * {@code GlobalExceptionHandler}. Deixar o container responder produziria a
 * pagina de erro padrao do Spring, que o {@code client.ts} do frontend nao
 * sabe ler — ele espera {@code success}/{@code data}/{@code error} em toda
 * resposta. Mensagem identica a do {@code errorResponseBuilder} do Node.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	private static final Duration JANELA = Duration.ofMinutes(1);

	private static final int LIMITE_PADRAO = 30;

	private static final int LIMITE_AGENDAMENTO = 10;

	private static final int LIMITE_HORARIOS = 30;

	/** O {@code authRateLimit} do Node: trava a forca bruta de senha. */
	private static final int LIMITE_AUTENTICACAO = 5;

	private static final String PREFIXO_API = "/api/v1";

	private final ProxyManager<String> baldes;

	private final ClientIpResolver ips;

	private final ObjectMapper json;

	public RateLimitFilter(ProxyManager<String> baldes, ClientIpResolver ips, ObjectMapper json) {
		this.baldes = baldes;
		this.ips = ips;
		this.json = json;
	}

	/**
	 * O limite vale so para a API.
	 *
	 * <p>{@code /actuator/health} fica de fora de proposito: o healthcheck do
	 * container bate nele a cada poucos segundos, sempre do mesmo IP, e seria o
	 * primeiro a estourar o balde — derrubando o proprio container que o
	 * healthcheck deveria vigiar.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest requisicao) {
		return !requisicao.getRequestURI().startsWith(PREFIXO_API);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
			FilterChain cadeia) throws ServletException, IOException {

		String ip = this.ips.resolver(requisicao);
		Balde balde = baldeDe(requisicao);
		Bucket bucket = this.baldes.builder()
				.build(balde.chave(ip), () -> configuracao(balde.limite()));

		if (bucket.tryConsume(1)) {
			cadeia.doFilter(requisicao, resposta);
			return;
		}

		// Sem o IP no log nao da para distinguir ataque de cliente legitimo
		// preso num loop de retry.
		//
		// O valor e sempre um literal de IP: o ClientIpResolver valida tudo
		// que vem de cabecalho antes de devolver, e cai para o endereco do
		// socket quando nao e. Isso importa aqui porque o valor vinha de
		// cabecalho sem validacao nenhuma, e um "\n" no meio dele abria linha
		// nova no log -- qualquer coisa podia ser escrita como se fosse um
		// registro da aplicacao.
		log.warn("Rate limit estourado: balde={} ip={} rota={}", balde.nome(), ip,
				requisicao.getRequestURI());
		responder429(resposta);
	}

	private static Balde baldeDe(HttpServletRequest requisicao) {
		String rota = requisicao.getRequestURI();
		boolean post = "POST".equalsIgnoreCase(requisicao.getMethod());

		if (post && rota.startsWith(PREFIXO_API + "/appointments")) {
			return new Balde("agendamento", LIMITE_AGENDAMENTO);
		}
		if (post && rota.startsWith(PREFIXO_API + "/available-times")) {
			return new Balde("horarios", LIMITE_HORARIOS);
		}
		if (post && rota.startsWith(PREFIXO_API + "/auth")) {
			return new Balde("autenticacao", LIMITE_AUTENTICACAO);
		}
		return new Balde("global", LIMITE_PADRAO);
	}

	private static BucketConfiguration configuracao(int limite) {
		return BucketConfiguration.builder()
				// Intervally e nao greedy: a janela inteira e reposta de uma
				// vez a cada minuto, como o timeWindow do fastify-rate-limit.
				// O greedy repoe aos poucos e deixaria passar mais pedidos no
				// comeco de cada janela.
				.addLimit(Bandwidth.builder().capacity(limite).refillIntervally(limite, JANELA).build())
				.build();
	}

	private void responder429(HttpServletResponse resposta) throws IOException {
		resposta.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
		resposta.setCharacterEncoding("UTF-8");
		resposta.getWriter().write(this.json.writeValueAsString(ApiResponse.erro(
				"Too many requests", "Rate limit exceeded. Try again in 1 minute.")));
	}

	/**
	 * Um balde: o nome que entra na chave e o limite por minuto.
	 *
	 * <p>A chave e {@code rate-limit:<balde>:<ip>}. O nome do balde dentro da
	 * chave e o que mantem os limites independentes entre rotas.
	 */
	private record Balde(String nome, int limite) {

		String chave(String ip) {
			return "rate-limit:" + this.nome + ":" + ip;
		}

	}

}
