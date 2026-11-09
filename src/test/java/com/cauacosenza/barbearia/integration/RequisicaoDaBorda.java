package com.cauacosenza.barbearia.integration;

import java.util.UUID;
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
 * <p><b>Por que precisa do token.</b> Antes bastava mandar
 * {@code CF-Connecting-IP: teste-<uuid>}: o resolvedor aceitava o cabecalho de
 * qualquer um e nao validava o formato. O cabecalho continua sendo o mesmo,
 * mas agora so e lido quando o {@code X-Edge-Token} confere, e so se for
 * literal de IP — as duas coisas que fecharam a brecha de rate limit.
 *
 * <p><b>Por que IPv6 de documentacao e um contador.</b> Os enderecos saem de
 * {@code 2001:db8::/32}, a faixa que a RFC 3849 reserva para exemplo, com os
 * 32 bits finais vindos de um contador. Sorteio em {@code 203.0.113.x}
 * colidiria de vez em quando e produziria um 429 intermitente — o pior tipo
 * de teste instavel. Com contador, duas requisicoes nunca compartilham balde.
 *
 * <h2>E por que tambem um prefixo por JVM</h2>
 *
 * <p>O contador sozinho isola requisicoes <b>dentro</b> de uma execucao e nao
 * serve para nada entre execucoes: ele reinicia em 1, entao toda JVM produz a
 * mesma sequencia. Enquanto cada execucao tinha o seu proprio Redis isso era
 * irrelevante. Com {@code withReuse(true)} deixou de ser — o Redis sobrevive,
 * as chaves de rate limit duram uma hora e a janela do balde e de um minuto.
 *
 * <p>Foi reproduzido: dois {@code RateLimitTest#baldeDeAgendamento} em
 * sequencia, e o segundo falhava em "pedido 1 nao deveria ser limitado",
 * porque as duas JVMs computaram a mesma chave
 * {@code rate-limit:agendamento:2001:db8::0:1}. Os 64 bits de prefixo
 * sorteados uma vez por processo fazem cada execucao ocupar uma faixa so
 * dela, e o mesmo vale para JVMs <b>simultaneas</b> — que e o caso dos
 * minions do PIT.
 */
public final class RequisicaoDaBorda {

	private static final AtomicInteger CONTADOR = new AtomicInteger();

	/**
	 * Os quatro grupos do meio do endereco, sorteados uma vez por processo.
	 *
	 * <p>Com {@code 2001:db8} na frente e dois grupos de contador no fim, sao
	 * os 8 grupos de um IPv6 completo — sem {@code "::"}, que o
	 * {@code EnderecoIp} aceita mas que aqui nao faz falta.
	 */
	private static final String PREFIXO_DESTA_JVM = sortearPrefixo();

	private RequisicaoDaBorda() {
	}

	private static String sortearPrefixo() {
		long aleatorio = UUID.randomUUID().getMostSignificantBits();
		return "2001:db8:%x:%x:%x:%x".formatted(
				(aleatorio >>> 48) & 0xffff,
				(aleatorio >>> 32) & 0xffff,
				(aleatorio >>> 16) & 0xffff,
				aleatorio & 0xffff);
	}

	/** Acrescenta o token da borda e um {@code CF-Connecting-IP} inedito. */
	public static MockHttpServletRequestBuilder comIpProprio(MockHttpServletRequestBuilder pedido) {
		return comIp(pedido, proximoIp());
	}

	/** Acrescenta o token da borda e o IP dado. */
	public static MockHttpServletRequestBuilder comIp(MockHttpServletRequestBuilder pedido,
			String ip) {

		return pedido
				.header(ClientIpResolver.X_EDGE_TOKEN, SegredosDeTeste.TOKEN_DA_BORDA)
				.header(ClientIpResolver.CF_CONNECTING_IP, ip);
	}

	/**
	 * Um IPv6 de documentacao que nenhuma outra requisicao usou — nem nesta
	 * execucao, nem em outra que esteja dividindo o mesmo Redis.
	 */
	public static String proximoIp() {
		int sequencial = CONTADOR.incrementAndGet();
		return "%s:%x:%x".formatted(PREFIXO_DESTA_JVM, sequencial >>> 16, sequencial & 0xffff);
	}

}
