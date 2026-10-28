package com.cauacosenza.barbearia.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Monta os cookies de sessao com as flags exatas do backend Node.
 *
 * <p>Centraliza porque <b>cada uma dessas flags ja quebrou alguma coisa</b>, e
 * espalhadas pelo controller a proxima seria copiada de memoria.
 *
 * <table border="1">
 *   <caption>As flags, e o valor em cada ambiente</caption>
 *   <tr><th>flag</th><th>dev</th><th>prod</th></tr>
 *   <tr><td>{@code httpOnly}</td><td colspan="2">sempre {@code true}</td></tr>
 *   <tr><td>{@code secure}</td><td>false</td><td>true</td></tr>
 *   <tr><td>{@code sameSite}</td><td>{@code Strict}</td><td>{@code None}</td></tr>
 *   <tr><td>{@code domain}</td><td>ausente</td><td>{@code .barbeariajhonatan.com}</td></tr>
 *   <tr><td>{@code path} (access)</td><td colspan="2">{@code /}</td></tr>
 *   <tr><td>{@code path} (refresh)</td><td colspan="2">{@code /api/v1/auth/refresh}</td></tr>
 *   <tr><td>{@code maxAge}</td><td colspan="2">15 min / 7 dias</td></tr>
 * </table>
 *
 * <h2>Por que cada uma importa</h2>
 *
 * <p><b>{@code httpOnly}</b> — JavaScript nao le o cookie. E o que torna um XSS
 * no painel incapaz de roubar a sessao. Tem um efeito colateral visivel: o
 * {@code AuthContext} do frontend <i>nao consegue</i> saber se esta logado
 * lendo o cookie, e por isso chama {@code GET /admin/health} no boot — o 401
 * dessa chamada e a resposta esperada, nao um erro.
 *
 * <p><b>{@code domain} com ponto na frente, em producao.</b> Sem ele o Safari
 * do iPhone descarta o cookie e o painel nao loga no celular. Foi um bug real
 * e caro de diagnosticar, porque funciona em todo navegador de desktop. O
 * ponto faz o cookie valer para o dominio e todos os subdominios, que e o que
 * permite o front em {@code www} falar com a API em outro subdominio.
 *
 * <p><b>{@code sameSite=None} em producao</b> — front e API ficam em
 * subdominios diferentes, e {@code Strict} faria o navegador nao enviar o
 * cookie. {@code None} <b>exige</b> {@code Secure}: a combinacao
 * {@code None} sem {@code Secure} e recusada pelos navegadores modernos, e e
 * por isso que as duas flags mudam juntas. Em dev os dois lados sao
 * {@code localhost} (mesmo site, portas diferentes), entao {@code Strict}
 * funciona e e mais restritivo.
 *
 * <p><b>{@code path} do refresh.</b> O refresh token so e enviado em
 * {@code POST /api/v1/auth/refresh}. Toda outra requisicao do painel — e sao
 * muitas — trafega sem ele. Reduz a superficie: um log de proxy mal
 * configurado, ou um erro que ecoe cabecalhos, expoe o access token de 15
 * minutos, nao a credencial de sete dias.
 *
 * <h2>Limpar nao e o mesmo que criar</h2>
 *
 * <p>{@link #limparAccessToken()} e {@link #limparRefreshToken()} repetem
 * {@code domain}, {@code path}, {@code secure} e {@code sameSite} do cookie
 * original. O navegador identifica um cookie pela trinca
 * <i>nome + domain + path</i>: um {@code Set-Cookie} de expiracao que erre
 * qualquer um dos tres cria um <i>segundo</i> cookie vazio e deixa o original
 * intacto — o usuario clica em sair e continua logado.
 */
@Component
public class CookieFactory {

	public static final String COOKIE_ACCESS = "access_token";

	public static final String COOKIE_REFRESH = "refresh_token";

	/** O refresh so viaja nesta rota. */
	public static final String PATH_REFRESH = "/api/v1/auth/refresh";

	private static final String PATH_RAIZ = "/";

	private final boolean producao;

	private final String dominio;

	/**
	 * @param producao espelha o {@code NODE_ENV === 'production'} do Node. O
	 *     default segue o perfil ativo, e pode ser forcado por
	 *     {@code app.cookies.producao} — o que existe para o teste poder
	 *     verificar as flags de producao sem subir no perfil {@code prod}.
	 * @param dominio o domain do cookie em producao; vazio significa ausente
	 */
	public CookieFactory(
			@Value("${app.cookies.producao:false}") boolean producao,
			@Value("${app.cookies.dominio:}") String dominio) {

		this.producao = producao;
		this.dominio = dominio;
	}

	public ResponseCookie accessToken(String valor) {
		return base(COOKIE_ACCESS, valor, PATH_RAIZ)
				.maxAge(JwtService.VALIDADE_ACCESS)
				.build();
	}

	public ResponseCookie refreshToken(String valor) {
		return base(COOKIE_REFRESH, valor, PATH_REFRESH)
				.maxAge(JwtService.VALIDADE_REFRESH)
				.build();
	}

	public ResponseCookie limparAccessToken() {
		return base(COOKIE_ACCESS, "", PATH_RAIZ).maxAge(Duration.ZERO).build();
	}

	public ResponseCookie limparRefreshToken() {
		return base(COOKIE_REFRESH, "", PATH_REFRESH).maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String nome, String valor, String path) {
		ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(nome, valor)
				.httpOnly(true)
				.secure(this.producao)
				.sameSite(this.producao ? "None" : "Strict")
				.path(path);

		if (this.producao && StringUtils.hasText(this.dominio)) {
			cookie.domain(this.dominio);
		}
		return cookie;
	}

}
