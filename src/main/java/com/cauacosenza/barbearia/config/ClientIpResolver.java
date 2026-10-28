package com.cauacosenza.barbearia.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Descobre o IP real do cliente atras do Cloudflare e do Railway.
 *
 * <p><b>Por que isto nao e opcional.</b> Em producao a requisicao chega pelo
 * Cloudflare e pelo proxy do Railway, entao
 * {@code request.getRemoteAddr()} devolve o endereco do <i>proxy</i>, igual
 * para todo mundo. Um rate limit com essa chave coloca todos os clientes no
 * mesmo balde: o 31.o pedido do dia inteiro leva 429, e basta um visitante
 * ativo para derrubar a reserva de todos os outros.
 *
 * <p><b>E o que o Node faz hoje.</b> O {@code keyGenerator} do
 * {@code @fastify/rate-limit} usa {@code req.ip}, e o Fastify so popula esse
 * campo a partir dos cabecalhos de proxy quando {@code trustProxy} esta
 * ligado — o {@code index.ts} cria a instancia com
 * {@code Fastify({ logger: false })} e nunca liga. Ou seja: <b>o rate limit do
 * Node compartilha um unico balde entre todos os clientes</b>. Na pratica quem
 * limita por IP de verdade e a regra do Cloudflare, que usa
 * {@code ip.src} na borda. Aqui a divergencia e deliberada e e uma correcao;
 * esta registrada no README.
 *
 * <h2>A ordem de confianca</h2>
 *
 * <ol>
 *   <li>{@code CF-Connecting-IP} — posto pelo Cloudflare, que sobrescreve o
 *       que o cliente tenha mandado. E o mesmo cabecalho que o worker em
 *       {@code cloudflare/src/index.ts} le.
 *   <li>{@code X-Forwarded-For}, <b>primeira</b> entrada — o cliente original;
 *       as seguintes sao os proxies do caminho.
 *   <li>{@code getRemoteAddr()} — sem proxy nenhum, em desenvolvimento.
 * </ol>
 *
 * <p>Os dois cabecalhos sao forjaveis por quem fala direto com a aplicacao,
 * sem passar pelo Cloudflare. Quem fizer isso escolhe a propria chave de rate
 * limit e some do balde dos outros, mas nao ganha acesso a nada: sao usados
 * <b>so</b> para limitar taxa, nunca para autorizar. Fechar essa brecha exige
 * que o Railway so aceite conexao vinda do Cloudflare, o que e configuracao de
 * infraestrutura e nao de codigo.
 */
@Component
public class ClientIpResolver {

	/** Posto pelo Cloudflare; sobrescreve o que o cliente tenha enviado. */
	private static final String CF_CONNECTING_IP = "CF-Connecting-IP";

	private static final String X_FORWARDED_FOR = "X-Forwarded-For";

	/** Quando nem cabecalho nem socket dizem de onde veio o pedido. */
	private static final String DESCONHECIDO = "unknown";

	public String resolver(HttpServletRequest requisicao) {
		String cloudflare = requisicao.getHeader(CF_CONNECTING_IP);
		if (StringUtils.hasText(cloudflare)) {
			return cloudflare.trim();
		}

		String encaminhado = requisicao.getHeader(X_FORWARDED_FOR);
		if (StringUtils.hasText(encaminhado)) {
			// "cliente, proxy1, proxy2" -- o primeiro e a origem.
			int virgula = encaminhado.indexOf(',');
			String primeiro = (virgula >= 0) ? encaminhado.substring(0, virgula) : encaminhado;
			if (StringUtils.hasText(primeiro)) {
				return primeiro.trim();
			}
		}

		String socket = requisicao.getRemoteAddr();
		return StringUtils.hasText(socket) ? socket : DESCONHECIDO;
	}

}
