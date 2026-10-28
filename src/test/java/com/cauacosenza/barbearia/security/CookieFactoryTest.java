package com.cauacosenza.barbearia.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/**
 * As flags dos cookies, nos dois ambientes.
 *
 * <p>Teste puro: a {@link CookieFactory} so depende de duas propriedades, e
 * instancia-la direto permite verificar o modo <b>producao</b> sem subir a
 * aplicacao com o perfil {@code prod} — que exigiria banco, Redis e todas as
 * variaveis obrigatorias.
 *
 * <p>Cada asercao aqui corresponde a uma linha do {@code COOKIE_OPTS} do
 * {@code authController.ts}. Sao quatro flags e um domain, e <b>cada uma ja
 * quebrou alguma coisa</b> — o {@code Domain} com ponto e o caso mais caro: o
 * painel nao logava no iPhone.
 */
class CookieFactoryTest {

	private static final String DOMINIO = ".barbeariajhonatan.com";

	// =======================================================================
	// Desenvolvimento
	// =======================================================================

	@Nested
	@DisplayName("desenvolvimento")
	class Desenvolvimento {

		private final CookieFactory cookies = new CookieFactory(false, "");

		@Test
		@DisplayName("access: HttpOnly, Path=/, SameSite=Strict, 15 minutos")
		void access() {
			String cookie = cookies.accessToken("token-abc").toString();

			assertThat(cookie)
					.startsWith("access_token=token-abc")
					.contains("HttpOnly")
					.contains("Path=/")
					.contains("SameSite=Strict")
					.contains("Max-Age=900");
		}

		@Test
		@DisplayName("sem Secure e sem Domain: localhost nao tem HTTPS nem dominio")
		void semSecureNemDomain() {
			// Secure em dev faria o navegador descartar o cookie em http://.
			assertThat(cookies.accessToken("x").toString())
					.doesNotContain("Secure")
					.doesNotContain("Domain");
		}

		@Test
		@DisplayName("refresh: Path restrito a rota de refresh, 7 dias")
		void refresh() {
			String cookie = cookies.refreshToken("token-xyz").toString();

			assertThat(cookie)
					.startsWith("refresh_token=token-xyz")
					.contains("HttpOnly")
					// So viaja nesta rota: toda outra requisicao do painel
					// trafega sem a credencial de sete dias.
					.contains("Path=/api/v1/auth/refresh")
					.contains("Max-Age=604800");
		}

		@Test
		@DisplayName("SameSite=Strict e possivel porque front e API sao o mesmo site")
		void sameSiteStrict() {
			// localhost:5173 -> localhost:8080 e same-site: o registrable
			// domain e o mesmo, a porta nao conta.
			assertThat(cookies.refreshToken("x").toString()).contains("SameSite=Strict");
		}

	}

	// =======================================================================
	// Producao
	// =======================================================================

	@Nested
	@DisplayName("producao")
	class Producao {

		private final CookieFactory cookies = new CookieFactory(true, DOMINIO);

		@Test
		@DisplayName("Secure e SameSite=None andam juntos — um exige o outro")
		void secureENone() {
			// SameSite=None sem Secure e recusado pelos navegadores modernos.
			// None e necessario porque front e API ficam em subdominios
			// diferentes e Strict faria o cookie nao ser enviado.
			String cookie = cookies.accessToken("x").toString();

			assertThat(cookie).contains("Secure").contains("SameSite=None");
		}

		@Test
		@DisplayName("Domain com PONTO na frente — sem ele o Safari do iPhone descarta")
		void domainComPonto() {
			// Bug real e caro de diagnosticar: funciona em todo navegador de
			// desktop e falha so no iPhone. O ponto faz o cookie valer para o
			// dominio e todos os subdominios.
			assertThat(cookies.accessToken("x").toString())
					.contains("Domain=" + DOMINIO);
			assertThat(cookies.refreshToken("x").toString())
					.contains("Domain=" + DOMINIO);

			// A asercao que importa: o ponto esta la.
			assertThat(DOMINIO).startsWith(".");
		}

		@Test
		@DisplayName("os paths e as validades nao mudam entre ambientes")
		void pathsEValidades() {
			assertThat(cookies.accessToken("x").toString())
					.contains("Path=/").contains("Max-Age=900");
			assertThat(cookies.refreshToken("x").toString())
					.contains("Path=/api/v1/auth/refresh").contains("Max-Age=604800");
		}

		@Test
		@DisplayName("HttpOnly em producao tambem — o XSS nao le a sessao")
		void httpOnly() {
			assertThat(cookies.accessToken("x").toString()).contains("HttpOnly");
			assertThat(cookies.refreshToken("x").toString()).contains("HttpOnly");
		}

		@Test
		@DisplayName("domain vazio: a flag some, em vez de sair 'Domain='")
		void dominioVazio() {
			// Um Domain vazio seria cabecalho invalido. A configuracao permite
			// producao sem dominio proprio (atras de um unico host).
			assertThat(new CookieFactory(true, "").accessToken("x").toString())
					.doesNotContain("Domain");
		}

	}

	// =======================================================================
	// Limpeza
	// =======================================================================

	@Nested
	@DisplayName("limpar repete domain, path, secure e sameSite do original")
	class Limpeza {

		private final CookieFactory cookies = new CookieFactory(true, DOMINIO);

		@Test
		@DisplayName("os cookies de limpeza batem com os de criacao, atributo a atributo")
		void mesmosAtributos() {
			// O navegador identifica um cookie por nome + domain + path. Um
			// Set-Cookie de expiracao que erre qualquer um dos tres cria um
			// SEGUNDO cookie vazio e deixa o original de pe: o usuario clica
			// em sair e continua logado.
			verificarPar(cookies.accessToken("x"), cookies.limparAccessToken());
			verificarPar(cookies.refreshToken("x"), cookies.limparRefreshToken());
		}

		@Test
		@DisplayName("o valor fica vazio e Max-Age=0")
		void expiraImediatamente() {
			assertThat(cookies.limparAccessToken().toString())
					.startsWith("access_token=;")
					.contains("Max-Age=0");
			assertThat(cookies.limparRefreshToken().toString())
					.startsWith("refresh_token=;")
					.contains("Max-Age=0");
		}

		private void verificarPar(ResponseCookie criado, ResponseCookie limpo) {
			assertThat(limpo.getName()).isEqualTo(criado.getName());
			assertThat(limpo.getPath()).isEqualTo(criado.getPath());
			assertThat(limpo.getDomain()).isEqualTo(criado.getDomain());
			assertThat(limpo.isSecure()).isEqualTo(criado.isSecure());
			assertThat(limpo.getSameSite()).isEqualTo(criado.getSameSite());
			assertThat(limpo.isHttpOnly()).isEqualTo(criado.isHttpOnly());
		}

	}

}
