package com.cauacosenza.barbearia.config;

/**
 * Reconhece um literal de endereco IP, v4 ou v6.
 *
 * <p><b>Por que escrito a mao.</b> O projeto nao tem Guava nem
 * commons-validator, e {@code InetAddress.ofLiteral} so existe a partir do Java
 * 22 — aqui e Java 21. {@code InetAddress.getByName} nao serve: diante de algo
 * que nao e literal ele <b>resolve DNS</b>, o que transformaria um cabecalho
 * HTTP de origem desconhecida em consulta de rede dentro de um filtro, na
 * thread da requisicao. Um atacante escolheria o nome a resolver.
 *
 * <p><b>Para que serve.</b> O valor aprovado aqui vira chave de balde no Redis
 * e vai para o log do {@link RateLimitFilter}. Recusar o que nao e IP fecha
 * duas coisas de uma vez: texto arbitrario deixa de criar chave no Redis (a
 * cardinalidade passa a ser limitada pelo espaco de enderecos, nao pela
 * imaginacao de quem chama) e deixa de haver {@code \n} no meio de uma linha de
 * log.
 *
 * <h2>Duas recusas deliberadas</h2>
 *
 * <ul>
 *   <li><b>Zero a esquerda</b> em octeto v4. {@code 01.2.3.4} e
 *       {@code 1.2.3.4} sao o mesmo endereco para quem le e duas chaves
 *       diferentes no Redis — ou seja, dois baldes para um cliente so. Alem
 *       disso a leitura de octeto com zero a esquerda diverge entre
 *       bibliotecas (ha quem interprete como octal), e divergencia de leitura
 *       entre a borda e a aplicacao e exatamente o tipo de coisa que vira
 *       brecha.
 *   <li><b>Identificador de zona</b> ({@code fe80::1%eth0}). E escopo local de
 *       uma maquina, nao identifica cliente nenhum atraves de um proxy.
 * </ul>
 *
 * <p><b>O que isto NAO faz: normalizar.</b> {@code ::1} e
 * {@code 0:0:0:0:0:0:0:1} sao o mesmo endereco e continuariam sendo duas
 * chaves. Nao e problema na pratica porque, nos dois caminhos em que o valor e
 * aceito, quem escreve o texto e sempre o mesmo componente — a Cloudflare,
 * via {@code CF-Connecting-IP}, ou o proxy do Railway, via a ultima entrada do
 * {@code X-Forwarded-For} — e cada um escreve de uma forma so. Normalizar
 * exigiria parsear para bytes e reimprimir, o que e bem mais codigo do que
 * reconhecer, para resolver uma duplicacao que ninguem consegue provocar.
 */
final class EnderecoIp {

	/** Um grupo de IPv6 tem de 1 a 4 digitos hexadecimais. */
	private static final int MAX_DIGITOS_DO_GRUPO = 4;

	/** Um IPv6 completo tem 8 grupos de 16 bits. */
	private static final int GRUPOS_DE_UM_IPV6 = 8;

	/** Um sufixo IPv4 dentro de um IPv6 ocupa dois grupos. */
	private static final int GRUPOS_DE_UM_SUFIXO_IPV4 = 2;

	private static final int OCTETOS_DE_UM_IPV4 = 4;

	private static final int MAIOR_OCTETO = 255;

	private EnderecoIp() {
	}

	/** O texto e um literal de IP v4 ou v6? */
	static boolean valido(String valor) {
		if (valor == null || valor.isEmpty()) {
			return false;
		}
		return (valor.indexOf(':') >= 0) ? ehIpv6(valor) : ehIpv4(valor);
	}

	// =======================================================================

	static boolean ehIpv4(String valor) {
		int octetos = 0;
		int inicio = 0;

		while (inicio <= valor.length()) {
			int ponto = valor.indexOf('.', inicio);
			int fim = (ponto >= 0) ? ponto : valor.length();

			if (++octetos > OCTETOS_DE_UM_IPV4 || !ehOcteto(valor, inicio, fim)) {
				return false;
			}
			if (ponto < 0) {
				return octetos == OCTETOS_DE_UM_IPV4;
			}
			inicio = ponto + 1;
		}
		return false;
	}

	/** De 1 a 3 digitos decimais, valor ate 255, sem zero a esquerda. */
	private static boolean ehOcteto(String valor, int inicio, int fim) {
		int digitos = fim - inicio;
		if (digitos < 1 || digitos > 3) {
			return false;
		}
		if (digitos > 1 && valor.charAt(inicio) == '0') {
			return false;
		}

		int numero = 0;
		for (int i = inicio; i < fim; i++) {
			char caractere = valor.charAt(i);
			if (caractere < '0' || caractere > '9') {
				return false;
			}
			numero = numero * 10 + (caractere - '0');
		}
		return numero <= MAIOR_OCTETO;
	}

	// =======================================================================

	private static boolean ehIpv6(String valor) {
		// Escopo de interface: nao identifica cliente atraves de um proxy.
		if (valor.indexOf('%') >= 0) {
			return false;
		}

		int compressao = valor.indexOf("::");
		if (compressao >= 0 && valor.indexOf("::", compressao + 1) >= 0) {
			// "::" so pode aparecer uma vez -- com duas, a quantidade de
			// grupos que cada uma representa seria ambigua.
			return false;
		}

		if (compressao < 0) {
			return contarGrupos(valor, true) == GRUPOS_DE_UM_IPV6;
		}

		// O sufixo IPv4, quando existe, esta sempre no fim do endereco --
		// portanto no lado direito da compressao.
		int esquerda = contarGrupos(valor.substring(0, compressao), false);
		int direita = contarGrupos(valor.substring(compressao + 2), true);
		if (esquerda < 0 || direita < 0) {
			return false;
		}
		// "::" representa pelo menos um grupo de zeros, entao os grupos
		// escritos tem que sobrar espaco para ele.
		return esquerda + direita < GRUPOS_DE_UM_IPV6;
	}

	/**
	 * Quantos grupos de 16 bits o lado escreve, ou {@code -1} se for invalido.
	 *
	 * <p>Lado vazio conta zero: e o que ha dos dois lados de um {@code "::"}
	 * sozinho e a esquerda de {@code "::1"}.
	 */
	private static int contarGrupos(String lado, boolean permiteSufixoIpv4) {
		if (lado.isEmpty()) {
			return 0;
		}

		int grupos = 0;
		int inicio = 0;

		while (true) {
			int doisPontos = lado.indexOf(':', inicio);
			int fim = (doisPontos >= 0) ? doisPontos : lado.length();

			if (fim == inicio) {
				// Parte vazia: ":" solto na ponta, ou ":::" no original.
				return -1;
			}

			boolean ultima = doisPontos < 0;
			if (ultima && permiteSufixoIpv4 && lado.indexOf('.', inicio) >= 0) {
				if (!ehIpv4(lado.substring(inicio))) {
					return -1;
				}
				grupos += GRUPOS_DE_UM_SUFIXO_IPV4;
			}
			else {
				if (!ehGrupoHexadecimal(lado, inicio, fim)) {
					return -1;
				}
				grupos++;
			}

			if (grupos > GRUPOS_DE_UM_IPV6) {
				return -1;
			}
			if (ultima) {
				return grupos;
			}
			inicio = doisPontos + 1;
		}
	}

	private static boolean ehGrupoHexadecimal(String valor, int inicio, int fim) {
		if (fim - inicio > MAX_DIGITOS_DO_GRUPO) {
			return false;
		}
		for (int i = inicio; i < fim; i++) {
			if (Character.digit(valor.charAt(i), 16) < 0) {
				return false;
			}
		}
		return true;
	}

}
