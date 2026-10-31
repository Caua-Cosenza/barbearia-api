package com.cauacosenza.barbearia.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Descobre o IP do cliente atras da borda, sem acreditar em cabecalho que o
 * cliente possa escrever.
 *
 * <p><b>Por que isto nao e opcional.</b> Em producao a requisicao chega pela
 * borda da Cloudflare e pelo proxy do Railway, entao
 * {@code request.getRemoteAddr()} devolve o endereco do <i>proxy</i>, igual
 * para todo mundo. Um rate limit com essa chave coloca todos os clientes no
 * mesmo balde: o 31.o pedido do dia inteiro leva 429, e basta um visitante
 * ativo para derrubar a reserva de todos os outros.
 *
 * <h2>O que mudou, e por que</h2>
 *
 * <p>A versao anterior confiava em {@code CF-Connecting-IP} e, na falta dele,
 * na <b>primeira</b> entrada do {@code X-Forwarded-For}. Os dois sao
 * escolhidos pelo cliente quando o pedido nao passa pela borda — e o
 * {@code up.railway.app} e publico, o Railway nao oferece allowlist de IP na
 * entrada, entao <b>sempre</b> existe um caminho que nao passa pela borda.
 * Quem descobrisse isso escolhia a propria chave de balde: cabecalho diferente
 * a cada requisicao, balde novo a cada requisicao, rate limit inexistente —
 * inclusive o balde de 5 por minuto do {@code /auth}, que e a trava de forca
 * bruta de senha.
 *
 * <p>A primeira entrada do {@code X-Forwarded-For} era o pior dos dois: e um
 * cabecalho de <i>append</i>, em que cada proxy acrescenta a direita, entao a
 * entrada mais a esquerda e exatamente a parte que o cliente escreveu.
 *
 * <h2>A ordem de confianca</h2>
 *
 * <ol>
 *   <li><b>{@code X-Edge-Token} confere</b> — o pedido veio da borda, e o IP
 *       do cliente e o {@code X-Edge-Client-IP} que ela escreveu. Nenhum outro
 *       cabecalho e consultado.
 *   <li><b>Senao, a ULTIMA entrada do {@code X-Forwarded-For}</b> — o par que
 *       de fato abriu a conexao com a borda do Railway, acrescentado por ela.
 *       Nao e o visitante quando ha proxy legitimo no caminho, mas e o pior
 *       caso honesto: quem forja volta a cair no balde do proprio IP em vez de
 *       escolher o balde.
 *   <li><b>Senao, {@code getRemoteAddr()}</b> — sem proxy nenhum, em
 *       desenvolvimento.
 * </ol>
 *
 * <p><b>Por que um segredo e nao a faixa de IP da Cloudflare.</b> O
 * {@code getRemoteAddr()} nao distingue trafego da borda de trafego direto: o
 * proxy do Railway termina a conexao e o container sempre ve um endereco
 * interno. Restaria comparar a ultima entrada do {@code X-Forwarded-For} com
 * as faixas publicadas da Cloudflare, o que exige manter essa lista
 * atualizada. O token prova a mesma coisa sem lista para manter, e de quebra
 * dispensa adivinhar quantos saltos o caminho tem.
 *
 * <p><b>{@code CF-Connecting-IP} deixou de ser lido.</b> Com o contrato acima
 * ele e redundante, e enquanto fosse lido continuaria sendo forjavel fora da
 * Cloudflare. Isso tambem encerra a duvida sobre {@code X-Real-IP} poder
 * sobrescreve-lo em subrequest de Worker: a aplicacao parou de olhar qualquer
 * cabecalho que o cliente consiga escrever.
 *
 * <p><b>Nota de transicao.</b> Sem {@code EDGE_TOKEN} configurado — ou
 * enquanto o Worker nao acrescentar os dois cabecalhos — tudo cai no passo 2.
 * Vindo da Cloudflare, a ultima entrada do {@code X-Forwarded-For} e um IP de
 * saida <i>da Cloudflare</i>, igual para muita gente: os visitantes voltam a
 * dividir poucos baldes. O fallback e seguro, nao e utilizavel em regime
 * permanente.
 */
@Component
public class ClientIpResolver {

	/**
	 * Provado pela borda: so ela conhece o segredo.
	 *
	 * <p>Publico junto com {@link #X_EDGE_CLIENT_IP} porque os dois sao o
	 * contrato da borda — o que o Worker escreve e o que a aplicacao le.
	 */
	public static final String X_EDGE_TOKEN = "X-Edge-Token";

	/** O IP do visitante, lido e escrito pela borda. */
	public static final String X_EDGE_CLIENT_IP = "X-Edge-Client-IP";

	private static final String X_FORWARDED_FOR = "X-Forwarded-For";

	/**
	 * Quando nem cabecalho nem socket dizem de onde veio o pedido.
	 *
	 * <p>Um IP valido e nao a palavra {@code "unknown"} para que o valor
	 * devolvido por {@link #resolver} seja <b>sempre</b> um literal de IP — e
	 * dai o que o {@link RateLimitFilter} escreve no log tambem seja. Juntar
	 * esses pedidos num balde unico e deliberado e conservador: nao ha
	 * container de servlet em que isto aconteca com pedido legitimo.
	 */
	private static final String DESCONHECIDO = "0.0.0.0";

	/** Vazio desliga o passo 1: sem token configurado, nada e confiado. */
	private final byte[] segredoDaBorda;

	public ClientIpResolver(@Value("${EDGE_TOKEN:}") String segredoDaBorda) {
		this.segredoDaBorda = StringUtils.hasText(segredoDaBorda)
				? segredoDaBorda.getBytes(StandardCharsets.UTF_8) : null;
	}

	public String resolver(HttpServletRequest requisicao) {
		if (veioDaBorda(requisicao)) {
			// A borda se identificou, entao o cabecalho dela e a resposta --
			// mesmo que venha quebrado. Cair para o X-Forwarded-For aqui
			// devolveria a decisao a um cabecalho que o cliente escreve, e um
			// defeito na borda viraria uma brecha.
			return ipOuDesconhecido(requisicao, requisicao.getHeader(X_EDGE_CLIENT_IP));
		}

		return ipOuDesconhecido(requisicao, ultimaEntradaDeForwardedFor(requisicao));
	}

	/**
	 * O pedido traz o segredo da borda?
	 *
	 * <p>{@link MessageDigest#isEqual} e nao {@code equals}: a comparacao de
	 * {@code String} para na primeira diferenca, e o tempo que ela leva conta
	 * quantos caracteres iniciais estao certos. Com isso o segredo pode ser
	 * descoberto caractere a caractere, sem nunca ser adivinhado inteiro.
	 */
	private boolean veioDaBorda(HttpServletRequest requisicao) {
		if (this.segredoDaBorda == null) {
			return false;
		}
		String apresentado = requisicao.getHeader(X_EDGE_TOKEN);
		if (apresentado == null) {
			return false;
		}
		return MessageDigest.isEqual(apresentado.getBytes(StandardCharsets.UTF_8),
				this.segredoDaBorda);
	}

	/**
	 * A <b>ultima</b> entrada, que e a acrescentada pelo proxy mais proximo —
	 * o unico pedaco do cabecalho que o cliente nao escreveu.
	 */
	private static String ultimaEntradaDeForwardedFor(HttpServletRequest requisicao) {
		String encaminhado = requisicao.getHeader(X_FORWARDED_FOR);
		if (!StringUtils.hasText(encaminhado)) {
			return null;
		}
		return encaminhado.substring(encaminhado.lastIndexOf(',') + 1);
	}

	/**
	 * O candidato, se for um literal de IP; senao o endereco do socket.
	 *
	 * <p>A validacao e o que impede o valor de virar chave arbitraria no Redis
	 * e linha arbitraria no log. Ver {@link EnderecoIp}.
	 */
	private static String ipOuDesconhecido(HttpServletRequest requisicao, String candidato) {
		if (candidato != null) {
			String limpo = candidato.trim();
			if (EnderecoIp.valido(limpo)) {
				return limpo;
			}
		}

		String socket = requisicao.getRemoteAddr();
		return (socket != null && EnderecoIp.valido(socket)) ? socket : DESCONHECIDO;
	}

}
