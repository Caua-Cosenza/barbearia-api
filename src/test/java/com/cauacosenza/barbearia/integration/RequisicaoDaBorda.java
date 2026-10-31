package com.cauacosenza.barbearia.integration;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cauacosenza.barbearia.config.ClientIpResolver;

/**
 * Faz uma requisicao de teste parecer vinda da borda, com um IP proprio.
 *
 * <p><b>Por que cada requisicao precisa de um IP so dela.</b> O rate limit e
 * real nos testes de integracao — o balde de criacao de agendamento e de 10
 * por minuto e uma classe de teste faz mais do que isso. Sem variar a chave,
 * os ultimos testes de cada classe recebem 429 por um motivo que nada tem a
 * ver com o que verificam. Quem testa o limite em si fixa o IP de proposito.
 *
 * <p><b>Por que passou a precisar do token.</b> Antes bastava mandar
 * {@code CF-Connecting-IP: teste-<uuid>}: o resolvedor aceitava qualquer
 * cabecalho e nao validava o formato. Agora ele so confia em
 * {@code X-Edge-Client-IP} quando o {@code X-Edge-Token} confere, e so aceita
 * literal de IP — as duas coisas que fecharam a brecha de rate limit.
 *
 * <p><b>Por que IPv6 de documentacao e um contador.</b> Os enderecos saem de
 * {@code 2001:db8::/32}, a faixa que a RFC 3849 reserva para exemplo, com os
 * 32 bits finais vindos de um contador. Sorteio em {@code 203.0.113.x}
 * colidiria de vez em quando e produziria um 429 intermitente — o pior tipo
 * de teste instavel. Com contador, duas requisicoes nunca compartilham balde.
 */
public final class RequisicaoDaBorda {

	private static final AtomicInteger CONTADOR = new AtomicInteger();

	private RequisicaoDaBorda() {
	}

	/** Acrescenta o token da borda e um {@code X-Edge-Client-IP} inedito. */
	public static MockHttpServletRequestBuilder comIpProprio(MockHttpServletRequestBuilder pedido) {
		return comIp(pedido, proximoIp());
	}

	/** Acrescenta o token da borda e o IP dado. */
	public static MockHttpServletRequestBuilder comIp(MockHttpServletRequestBuilder pedido,
			String ip) {

		return pedido
				.header(ClientIpResolver.X_EDGE_TOKEN, SegredosDeTeste.TOKEN_DA_BORDA)
				.header(ClientIpResolver.X_EDGE_CLIENT_IP, ip);
	}

	/** Um IPv6 de documentacao que nenhuma outra requisicao da suite usou. */
	public static String proximoIp() {
		int sequencial = CONTADOR.incrementAndGet();
		return "2001:db8::%x:%x".formatted(sequencial >>> 16, sequencial & 0xffff);
	}

}
