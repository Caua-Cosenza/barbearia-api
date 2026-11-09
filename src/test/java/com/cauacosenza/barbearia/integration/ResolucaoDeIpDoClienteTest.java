package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cauacosenza.barbearia.config.ClientIpResolver;

/**
 * Qual valor vira a chave do balde de rate limit em cada ponto da cadeia.
 *
 * <p>A cadeia de producao e
 * {@code cliente -> Cloudflare -> proxy do Railway -> Spring Boot} — o
 * frontend chama {@code api.barbeariajhonatan.com}, que esta <i>proxied</i>,
 * e nao ha Worker no caminho. O que o container enxerga dela sao quatro
 * coisas: o {@code X-Edge-Token} que a Transform Rule acrescenta, o
 * {@code CF-Connecting-IP} que a Cloudflare escreve, o
 * {@code X-Forwarded-For} e o {@code getRemoteAddr()}. Cada teste aqui monta essas quatro coisas como elas
 * chegam em um cenario real e afirma <b>a chave literal no Redis</b> — nao o
 * comportamento do limite, que e consequencia.
 *
 * <p><b>Por que a chave literal e nao o 429.</b> Um teste de 429 confunde dois
 * motivos distintos de um pedido passar: "o balde ainda tem credito" e "o balde
 * e outro". Olhando a chave no Redis, a diferenca entre atribuir o pedido ao
 * cliente certo e atribui-lo a um valor escolhido pelo atacante fica explicita,
 * e a mensagem de falha diz qual valor foi usado.
 *
 * <p><b>Por que o {@code getRemoteAddr()} nao serve de discriminador.</b> O
 * proxy do Railway termina a conexao, entao o container ve um endereco interno
 * do Railway tanto no trafego vindo da borda quanto no direto. E por isso que
 * a prova de que o pedido veio da borda e um segredo, e nao o endereco de
 * origem.
 */
class ResolucaoDeIpDoClienteTest extends AbstractPostgresIntegrationTest {

	private static final String X_FORWARDED_FOR = "X-Forwarded-For";

	/** Rota do balde {@code global}: 30 por minuto e nao precisa de dados. */
	private static final String ROTA = "/api/v1/services";

	private static final String PREFIXO_DO_BALDE = "rate-limit:global:";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RedisClient redis;

	// =======================================================================
	// 1 — o caminho legitimo: a borda se identifica
	// =======================================================================

	@Test
	@DisplayName("1. token valido: a chave e o CF-Connecting-IP")
	void tokenValidoUsaOIpEscritoPelaBorda() throws Exception {
		String visitante = RequisicaoDaBorda.proximoIp();

		this.mvc.perform(RequisicaoDaBorda.comIp(pedido(umIpQualquer()), visitante));

		assertThat(baldeExiste(visitante))
				.as("o pedido vindo da borda deve cair no balde do visitante")
				.isTrue();
	}

	@Test
	@DisplayName("1b. token valido: nenhum outro cabecalho e consultado")
	void tokenValidoIgnoraOsDemaisCabecalhos() throws Exception {
		String visitante = RequisicaoDaBorda.proximoIp();
		String ruido = RequisicaoDaBorda.proximoIp();

		this.mvc.perform(RequisicaoDaBorda.comIp(pedido(umIpQualquer()), visitante)
				.header(X_FORWARDED_FOR, ruido)
				// X-Real-IP nunca foi lido, e sem Worker nao ha nem a duvida
				// sobre ele sobrescrever o CF-Connecting-IP em subrequest.
				.header("X-Real-IP", ruido));

		assertThat(baldeExiste(visitante)).isTrue();
		assertThat(baldeExiste(ruido))
				.as("com o token conferindo, nenhum outro cabecalho deveria ter sido lido")
				.isFalse();
	}

	// =======================================================================
	// 2 — o caminho direto: sem o segredo, nada do cliente e aceito
	// =======================================================================

	@Test
	@DisplayName("2. token ausente: CF-Connecting-IP e ignorado e vale o fallback")
	void semTokenOIpDaBordaEhIgnorado() throws Exception {
		String atacante = RequisicaoDaBorda.proximoIp();
		String forjado = RequisicaoDaBorda.proximoIp();

		// O cenario "requisicao direta no up.railway.app": o CF-Connecting-IP
		// e escrito pela Cloudflare, mas quem fala direto com o Railway
		// escreve o que quiser com esse nome.
		this.mvc.perform(pedido(umIpQualquer())
				.header(ClientIpResolver.CF_CONNECTING_IP, forjado)
				.header(X_FORWARDED_FOR, atacante));

		assertThat(baldeExiste(forjado))
				.as("sem o segredo, o cabecalho da Cloudflare e so mais um cabecalho de cliente")
				.isFalse();
		assertThat(baldeExiste(atacante))
				.as("o pedido deveria ser atribuido a quem abriu a conexao")
				.isTrue();
	}

	@Test
	@DisplayName("2b. token errado: idem")
	void tokenErradoNaoEhAceito() throws Exception {
		String atacante = RequisicaoDaBorda.proximoIp();
		String forjado = RequisicaoDaBorda.proximoIp();

		this.mvc.perform(pedido(umIpQualquer())
				.header(ClientIpResolver.X_EDGE_TOKEN,
						SegredosDeTeste.TOKEN_DA_BORDA + "-errado")
				.header(ClientIpResolver.CF_CONNECTING_IP, forjado)
				.header(X_FORWARDED_FOR, atacante));

		assertThat(baldeExiste(forjado)).isFalse();
		assertThat(baldeExiste(atacante)).isTrue();
	}

	/**
	 * O {@code X-Edge-Client-IP} nao e lido nem com o token conferindo.
	 *
	 * <p>Ele foi o cabecalho do contrato enquanto o desenho previa um Worker
	 * da Cloudflare escrevendo o IP. Nao ha Worker, e o cabecalho saiu do
	 * codigo — este teste existe para que ele nao volte por descuido,
	 * carregando um valor que hoje ninguem escreve e que, se alguem escrever,
	 * e o proprio cliente.
	 */
	@Test
	@DisplayName("2c. X-Edge-Client-IP nao e lido, mesmo com token valido")
	void oCabecalhoAntigoDaBordaNaoEhMaisLido() throws Exception {
		String visitante = RequisicaoDaBorda.proximoIp();
		String antigo = RequisicaoDaBorda.proximoIp();

		this.mvc.perform(RequisicaoDaBorda.comIp(pedido(umIpQualquer()), visitante)
				.header("X-Edge-Client-IP", antigo));

		assertThat(baldeExiste(antigo))
				.as("o cabecalho do desenho antigo voltou a ser lido")
				.isFalse();
		assertThat(baldeExiste(visitante))
				.as("a chave deveria ser o CF-Connecting-IP")
				.isTrue();
	}

	// =======================================================================
	// 3 — X-Forwarded-For: a ultima entrada, nunca a primeira
	// =======================================================================

	@Test
	@DisplayName("3. X-Forwarded-For forjado: a primeira entrada nao vira chave")
	void primeiraEntradaDeForwardedForNaoEhAceita() throws Exception {
		String atacante = RequisicaoDaBorda.proximoIp();
		String forjado = RequisicaoDaBorda.proximoIp();

		// "forjado, atacante": o atacante escreveu a primeira entrada, o
		// Railway acrescentou a segunda.
		this.mvc.perform(pedido(umIpQualquer())
				.header(X_FORWARDED_FOR, forjado + ", " + atacante));

		assertThat(baldeExiste(forjado))
				.as("""
						a entrada mais a esquerda do X-Forwarded-For e escrita \
						pelo cliente e virou a chave do balde""")
				.isFalse();
		assertThat(baldeExiste(atacante))
				.as("a chave deveria vir da ultima entrada, acrescentada pelo Railway")
				.isTrue();
	}

	@Test
	@DisplayName("3b. X-Forwarded-For com varias entradas: vale a ultima")
	void forwardedForComVariasEntradasUsaAUltima() throws Exception {
		String peer = RequisicaoDaBorda.proximoIp();
		String primeira = RequisicaoDaBorda.proximoIp();
		String meio = RequisicaoDaBorda.proximoIp();

		this.mvc.perform(pedido(umIpQualquer())
				.header(X_FORWARDED_FOR, primeira + ", " + meio + ", " + peer));

		assertThat(baldeExiste(peer)).isTrue();
		assertThat(baldeExiste(primeira)).isFalse();
		assertThat(baldeExiste(meio)).isFalse();
	}

	// =======================================================================
	// 4 — validacao do valor
	// =======================================================================

	@Test
	@DisplayName("4. CF-Connecting-IP que nao e IP: cai no remoteAddr")
	void valorQueNaoEhIpCaiNoRemoteAddr() throws Exception {
		String socket = RequisicaoDaBorda.proximoIp();
		String naoEhIp = "balde-escolhido-a-dedo";

		this.mvc.perform(RequisicaoDaBorda.comIp(pedido(socket), naoEhIp));

		assertThat(baldeExiste(naoEhIp))
				.as("texto arbitrario viraria chave no Redis e linha no log")
				.isFalse();
		assertThat(baldeExiste(socket))
				.as("sem um IP utilizavel, a chave e o endereco do socket")
				.isTrue();
	}

	/**
	 * Quebra de linha no cabecalho: o pedido morre antes, com 400.
	 *
	 * <p>Quem recusa e o {@code StrictHttpFirewall} do Spring Security, que
	 * nao admite caractere de controle em valor de cabecalho. A recusa e
	 * <b>preguicosa</b>: a excecao sai de dentro do
	 * {@code getHeader} — ou seja, e a leitura feita pelo
	 * {@code ClientIpResolver} que a dispara. Nenhum balde chega a ser criado
	 * e o filtro de rate limit nao chega a logar.
	 *
	 * <p>Ou seja, a injecao de log tem duas barreiras, e vale saber qual faz o
	 * que. O firewall pega o caractere de controle e so ele; o que ele deixa
	 * passar e texto imprimivel arbitrario, como
	 * {@code balde-escolhido-a-dedo} do teste acima — e isso quem fecha e a
	 * validacao de IP. A barreira da validacao contra a quebra de linha em si
	 * esta verificada em {@code EnderecoIpTest}, no nivel em que o firewall
	 * nao esta na frente.
	 */
	@Test
	@DisplayName("4b. valor com quebra de linha: 400 e nenhum balde")
	void valorComQuebraDeLinhaEhRecusadoAntes() throws Exception {
		String socket = RequisicaoDaBorda.proximoIp();
		String comQuebra = "203.0.113.9\nINFO  linha inventada pelo cliente";

		int status = this.mvc.perform(RequisicaoDaBorda.comIp(pedido(socket), comQuebra))
				.andReturn().getResponse().getStatus();

		assertThat(status).isEqualTo(400);
		assertThat(baldeExiste(comQuebra)).isFalse();
		assertThat(baldeExiste(socket))
				.as("o pedido nem chegou ao filtro de rate limit")
				.isFalse();
	}

	@Test
	@DisplayName("4c. ultima entrada do X-Forwarded-For invalida: cai no remoteAddr")
	void forwardedForInvalidoCaiNoRemoteAddr() throws Exception {
		String socket = RequisicaoDaBorda.proximoIp();

		this.mvc.perform(pedido(socket).header(X_FORWARDED_FOR, "203.0.113.5, nao-e-ip"));

		assertThat(baldeExiste("nao-e-ip")).isFalse();
		assertThat(baldeExiste("203.0.113.5"))
				.as("cair para a entrada anterior devolveria a escolha ao cliente")
				.isFalse();
		assertThat(baldeExiste(socket)).isTrue();
	}

	// =======================================================================
	// 5 — a consequencia, em termos de limite
	// =======================================================================

	@Test
	@DisplayName("variar o cabecalho a cada pedido nao escapa do limite de 30")
	void variarOCabecalhoNaoRenovaOBalde() throws Exception {
		String atacante = RequisicaoDaBorda.proximoIp();
		String socket = RequisicaoDaBorda.proximoIp();
		int limiteGlobal = 30;

		int ultimoStatus = 0;
		for (int i = 0; i <= limiteGlobal; i++) {
			// Um "IP de cliente" diferente a cada volta, em todos os
			// cabecalhos que ja foram confiados algum dia. So isso muda: a
			// conexao e sempre a mesma.
			String inventado = RequisicaoDaBorda.proximoIp();
			ultimoStatus = this.mvc.perform(pedido(socket)
					.header(ClientIpResolver.CF_CONNECTING_IP, inventado)
					.header("X-Edge-Client-IP", inventado)
					.header(X_FORWARDED_FOR, inventado + ", " + atacante))
					.andReturn().getResponse().getStatus();
		}

		assertThat(ultimoStatus)
				.as("""
						31 pedidos da mesma origem passaram porque cada um \
						trouxe um cabecalho diferente: o rate limit por IP nao \
						limita nada contra quem fala direto com o Railway""")
				.isEqualTo(429);
	}

	// =======================================================================
	// Apoio
	// =======================================================================

	/** Um GET com o {@code remoteAddr} que o container veria na conexao. */
	private static MockHttpServletRequestBuilder pedido(String remoteAddr) {
		return get(ROTA).with(requisicao -> {
			requisicao.setRemoteAddr(remoteAddr);
			return requisicao;
		});
	}

	/**
	 * Um {@code remoteAddr} para quando o teste nao olha para ele.
	 *
	 * <p>Ainda assim e unico: se a resolucao escorregar e cair no socket, o
	 * teste falha por nao achar o balde esperado, em vez de passar por acaso
	 * dentro de um balde que outro teste criou.
	 */
	private static String umIpQualquer() {
		return RequisicaoDaBorda.proximoIp();
	}

	/**
	 * O balde daquele valor existe no Redis?
	 *
	 * <p>Consulta a chave candidata por nome em vez de listar
	 * {@code rate-limit:*}: cada teste usa IPs proprios, entao nada depende de
	 * limpar o Redis nem da ordem de execucao da suite.
	 */
	private boolean baldeExiste(String ip) {
		try (var conexao = this.redis.connect()) {
			RedisCommands<String, String> comandos = conexao.sync();
			return comandos.exists(PREFIXO_DO_BALDE + ip) > 0;
		}
	}

}
