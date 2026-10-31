package com.cauacosenza.barbearia.integration;

/**
 * Os segredos FICTICIOS que o contexto de teste precisa para subir.
 *
 * <p>Ficam reunidos aqui para serem escritos uma vez so: antes disso a
 * {@code ENCRYPTION_KEY} ja aparecia copiada em tres classes, e a cada nova
 * variavel obrigatoria todas elas quebravam juntas.
 *
 * <p>São constantes no formato {@code "NOME=valor"} porque e assim que o
 * {@code @SpringBootTest(properties = ...)} as recebe, e valores de anotacao
 * precisam ser constantes de compilacao.
 *
 * <p><b>Nenhum valor de producao entra aqui.</b> A {@code ENCRYPTION_KEY} e a
 * mesma chave inventada que gerou {@code crypto-fixture-node.txt} — tem que
 * ser, para o teste de compatibilidade com o Node decifrar o fixture. Os dois
 * segredos de JWT sao apenas strings longas o suficiente para o HS256 (32
 * bytes), e <b>diferentes entre si</b>, porque e exatamente isso que os
 * testes de refresh verificam: um access token nao pode passar por refresh
 * token nem o contrario.
 */
public final class SegredosDeTeste {

	/** A chave que gerou o fixture do Node. Ver {@code CryptoServiceTest}. */
	public static final String CHAVE_DE_CRIPTOGRAFIA =
			"4f8a2c1e9b7d3a5f6e0c8b4d2a9f1e7c3b5d8a0f2e6c4b9d1a7f3e5c8b2d6a0f";

	public static final String ENCRYPTION_KEY = "ENCRYPTION_KEY=" + CHAVE_DE_CRIPTOGRAFIA;

	public static final String JWT_SECRET =
			"JWT_SECRET=segredo-de-teste-para-o-access-token-com-mais-de-32-bytes";

	public static final String JWT_REFRESH_SECRET =
			"JWT_REFRESH_SECRET=outro-segredo-de-teste-para-o-refresh-token-32-bytes";

	/**
	 * O segredo que prova, para o {@code ClientIpResolver}, que a requisicao
	 * veio da borda.
	 *
	 * <p>Esta no contexto de <b>todo</b> teste, e nao so no que verifica a
	 * resolucao de IP, porque e assim que as classes base conseguem dar um IP
	 * proprio a cada requisicao: sem o token, o {@code X-Edge-Client-IP} e
	 * ignorado e todas as requisicoes da suite caem no mesmo balde.
	 */
	public static final String TOKEN_DA_BORDA =
			"segredo-de-borda-para-teste-com-mais-de-32-caracteres";

	public static final String EDGE_TOKEN = "EDGE_TOKEN=" + TOKEN_DA_BORDA;

	/** Tudo que o contexto precisa, pronto para o {@code properties}. */
	public static final String[] TODOS =
			{ ENCRYPTION_KEY, JWT_SECRET, JWT_REFRESH_SECRET, EDGE_TOKEN };

	private SegredosDeTeste() {
	}

}
