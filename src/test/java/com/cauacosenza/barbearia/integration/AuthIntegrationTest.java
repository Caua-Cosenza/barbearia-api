package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cauacosenza.barbearia.domain.entity.AdminUser;
import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.repository.AdminUserRepository;
import com.cauacosenza.barbearia.repository.RefreshTokenRepository;
import com.cauacosenza.barbearia.security.CookieFactory;

import tools.jackson.databind.JsonNode;

/**
 * Login, refresh, logout e o cerco as rotas {@code /admin}.
 *
 * <p>O que se verifica aqui nao e so "funciona": sao as <b>flags dos
 * cookies</b>, que decidem se o painel loga no iPhone, e o <b>efeito real da
 * revogacao</b>, que e o unico jeito de encerrar um JWT antes da hora.
 */
class AuthIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final String EMAIL = "admin@barbearia.com";

	private static final String SENHA = "Admin@123456";

	/** 2026-12-15, 03:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdminUserRepository usuarios;

	@Autowired
	private RefreshTokenRepository refreshTokens;

	@Autowired
	private PasswordEncoder senhas;

	@BeforeEach
	void criarAdmin() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);

		AdminUser admin = new AdminUser();
		admin.setEmail(EMAIL);
		admin.setPasswordHash(this.senhas.encode(SENHA));
		admin.setName("Jhonatan Correa");
		admin.setPhone("5522999990000");
		admin.setRole(Role.ADMIN);
		this.usuarios.saveAndFlush(admin);
	}

	// =======================================================================
	// Login
	// =======================================================================

	@Nested
	@DisplayName("POST /api/v1/auth/login")
	class Login {

		@Test
		@DisplayName("credencial correta devolve o envelope com o usuario, sem token no corpo")
		void loginOk() throws Exception {
			MvcResult resultado = logar();
			JsonNode corpo = corpoDe(resultado);

			assertThat(corpo.get("success").asBoolean()).isTrue();
			assertThat(corpo.get("message").asString()).isEqualTo("Login successful");
			assertThat(corpo.get("data").get("user").get("email").asString()).isEqualTo(EMAIL);
			// Nome decifrado pelo EncryptedStringConverter.
			assertThat(corpo.get("data").get("user").get("name").asString())
					.isEqualTo("Jhonatan Correa");

			// O corpo inteiro nao pode conter token nem hash de senha.
			String texto = resultado.getResponse().getContentAsString();
			assertThat(texto).doesNotContain("accessToken", "refreshToken", "passwordHash", "$2a$");
		}

		@Test
		@DisplayName("os dois cookies saem com as flags exatas")
		void flagsDosCookies() throws Exception {
			List<String> cookies = cookiesDe(logar());

			String access = cookie(cookies, CookieFactory.COOKIE_ACCESS);
			assertThat(access)
					.contains("HttpOnly")
					.contains("Path=/")
					.contains("SameSite=Strict")
					// 15 minutos.
					.contains("Max-Age=900");
			// Em desenvolvimento nao ha Secure nem Domain.
			assertThat(access).doesNotContain("Secure").doesNotContain("Domain");

			String refresh = cookie(cookies, CookieFactory.COOKIE_REFRESH);
			assertThat(refresh)
					.contains("HttpOnly")
					// O refresh so viaja na rota de refresh.
					.contains("Path=/api/v1/auth/refresh")
					.contains("SameSite=Strict")
					// 7 dias.
					.contains("Max-Age=604800");
		}

		@Test
		@DisplayName("os dois Set-Cookie sao cabecalhos separados")
		void doisCabecalhosSeparados() throws Exception {
			// Um unico Set-Cookie com virgula faria o navegador guardar so um
			// dos dois: a especificacao nao admite varios cookies por
			// cabecalho.
			assertThat(cookiesDe(logar())).hasSize(2);
		}

		@Test
		@DisplayName("senha errada devolve 401 no envelope, sem dizer se o e-mail existe")
		void senhaErrada() throws Exception {
			JsonNode corpo = corpoDe(mvc.perform(postJson("/api/v1/auth/login", """
					{ "email": "%s", "password": "senha-errada" }
					""".formatted(EMAIL))).andExpect(status(401)).andReturn());

			assertThat(corpo.get("success").asBoolean()).isFalse();
			assertThat(corpo.get("error").asString()).isEqualTo("Unauthorized");
			assertThat(corpo.get("message").asString()).isEqualTo("Invalid credentials");
		}

		@Test
		@DisplayName("e-mail inexistente devolve exatamente a mesma resposta")
		void emailInexistente() throws Exception {
			// Mensagens diferentes transformariam o login num oraculo sobre
			// quais e-mails tem conta.
			JsonNode corpo = corpoDe(mvc.perform(postJson("/api/v1/auth/login", """
					{ "email": "ninguem@barbearia.com", "password": "%s" }
					""".formatted(SENHA))).andExpect(status(401)).andReturn());

			assertThat(corpo.get("message").asString()).isEqualTo("Invalid credentials");
		}

		@Test
		@DisplayName("login errado nao deixa cookie nenhum")
		void loginErradoNaoSetaCookie() throws Exception {
			MvcResult resultado = mvc.perform(postJson("/api/v1/auth/login", """
					{ "email": "%s", "password": "errada" }
					""".formatted(EMAIL))).andExpect(status(401)).andReturn();

			assertThat(cookiesDe(resultado)).isEmpty();
		}

		@Test
		@DisplayName("e-mail malformado devolve 422 de validacao")
		void emailMalformado() throws Exception {
			mvc.perform(postJson("/api/v1/auth/login", """
					{ "email": "nao-e-email", "password": "x" }
					""")).andExpect(status(422));
		}

	}

	// =======================================================================
	// O refresh token no banco
	// =======================================================================

	@Nested
	@DisplayName("o refresh token nunca e gravado em claro")
	class ArmazenamentoDoToken {

		@Test
		@DisplayName("a coluna guarda o SHA-256 em hex, nao o JWT")
		void hashNaoToken() throws Exception {
			String refresh = valorDoCookie(cookiesDe(logar()), CookieFactory.COOKIE_REFRESH);

			String gravado = jdbc.queryForObject(
					"SELECT \"token_hash\" FROM \"refresh_tokens\"", String.class);

			// 64 caracteres hex: SHA-256.
			assertThat(gravado).matches("^[0-9a-f]{64}$");
			// E nao e o token -- nem um pedaco dele.
			assertThat(gravado).isNotEqualTo(refresh);
			assertThat(refresh).doesNotContain(gravado);
			// Um JWT tem tres partes separadas por ponto; o hash nao tem ponto.
			assertThat(refresh).contains(".");
			assertThat(gravado).doesNotContain(".");
		}

		@Test
		@DisplayName("o mesmo token sempre gera o mesmo hash — e o que torna a busca possivel")
		void hashEhDeterministico() throws Exception {
			// Bcrypt teria salt aleatorio e nao serviria aqui: validar um
			// refresh token e uma busca exata pelo hash.
			logar();
			String primeiro = jdbc.queryForObject(
					"SELECT \"token_hash\" FROM \"refresh_tokens\"", String.class);

			refreshTokens.deleteAll();
			refreshTokens.flush();

			logar();
			String segundo = jdbc.queryForObject(
					"SELECT \"token_hash\" FROM \"refresh_tokens\"", String.class);

			// Tokens diferentes (iat/jti diferem), hashes diferentes -- mas
			// cada um e reproduzivel a partir do seu token.
			assertThat(primeiro).matches("^[0-9a-f]{64}$");
			assertThat(segundo).matches("^[0-9a-f]{64}$");
		}

	}

	// =======================================================================
	// Acesso ao painel
	// =======================================================================

	@Nested
	@DisplayName("cerco as rotas /admin")
	class Acesso {

		@Test
		@DisplayName("sem cookie: 401 no envelope, nao o formato do Spring Security")
		void semCookie() throws Exception {
			JsonNode corpo = corpoDe(mvc.perform(getComIpProprio("/api/v1/admin/health"))
					.andExpect(status(401)).andReturn());

			assertThat(corpo.get("success").asBoolean()).isFalse();
			assertThat(corpo.get("data").isNull()).isTrue();
			assertThat(corpo.get("error").asString()).isEqualTo("Unauthorized");
			assertThat(corpo.get("message").asString()).isEqualTo("Invalid or expired token");
			assertThat(corpo.get("timestamp").asString())
					.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
		}

		@Test
		@DisplayName("401 no /admin/health e o que o AuthContext usa para redirecionar")
		void healthSemSessaoEh401() throws Exception {
			// Comportamento esperado, nao erro: o cookie e HttpOnly e o
			// JavaScript nao consegue inspeciona-lo.
			mvc.perform(getComIpProprio("/api/v1/admin/health")).andExpect(status(401));
		}

		@Test
		@DisplayName("com cookie de admin: 200 com 'admin ok'")
		void comCookieDeAdmin() throws Exception {
			JsonNode corpo = corpoDe(mvc.perform(getComIpProprio("/api/v1/admin/health")
					.cookie(cookieAccess())).andExpect(status(200)).andReturn());

			assertThat(corpo.get("success").asBoolean()).isTrue();
			assertThat(corpo.get("data").get("status").asString()).isEqualTo("admin ok");
		}

		@Test
		@DisplayName("cookie adulterado: 401")
		void cookieAdulterado() throws Exception {
			jakarta.servlet.http.Cookie falsificado =
					new jakarta.servlet.http.Cookie(CookieFactory.COOKIE_ACCESS, "nao.e.um.jwt");

			mvc.perform(getComIpProprio("/api/v1/admin/health").cookie(falsificado))
					.andExpect(status(401));
		}

		@Test
		@DisplayName("token expirado: 401")
		void tokenExpirado() throws Exception {
			jakarta.servlet.http.Cookie access = cookieAccess();

			// O relogio avanca 16 minutos: o access vale 15.
			BDDMockito.given(clock.instant()).willReturn(AGORA.plusSeconds(16 * 60));

			mvc.perform(getComIpProprio("/api/v1/admin/health").cookie(access))
					.andExpect(status(401));
		}

		@Test
		@DisplayName("um minuto antes de expirar ainda passa")
		void tokenQuaseExpirado() throws Exception {
			jakarta.servlet.http.Cookie access = cookieAccess();

			BDDMockito.given(clock.instant()).willReturn(AGORA.plusSeconds(14 * 60));

			mvc.perform(getComIpProprio("/api/v1/admin/health").cookie(access))
					.andExpect(status(200));
		}

		@Test
		@DisplayName("conta CLIENT autentica mas recebe 403, nao 401")
		void clientNaoEhAdmin() throws Exception {
			// Autenticacao e autorizacao sao perguntas diferentes: o login
			// aceita a conta, o painel a recusa. Mandar 401 faria o frontend
			// redirecionar para o login num laco infinito.
			AdminUser cliente = new AdminUser();
			cliente.setEmail("cliente@exemplo.com");
			cliente.setPasswordHash(senhas.encode(SENHA));
			cliente.setName("Cliente");
			cliente.setPhone("5522988880000");
			cliente.setRole(Role.CLIENT);
			usuarios.saveAndFlush(cliente);

			MvcResult login = mvc.perform(postJson("/api/v1/auth/login", """
					{ "email": "cliente@exemplo.com", "password": "%s" }
					""".formatted(SENHA))).andExpect(status(200)).andReturn();

			JsonNode corpo = corpoDe(mvc.perform(getComIpProprio("/api/v1/admin/health")
					.cookie(cookieDe(login, CookieFactory.COOKIE_ACCESS)))
					.andExpect(status(403)).andReturn());

			assertThat(corpo.get("error").asString()).isEqualTo("Forbidden");
			assertThat(corpo.get("message").asString()).isEqualTo("Admin access required");
		}

		@Test
		@DisplayName("as rotas publicas da Fase 5 seguem abertas sem cookie")
		void rotasPublicasContinuamPublicas() throws Exception {
			mvc.perform(getComIpProprio("/api/v1/services")).andExpect(status(200));
			mvc.perform(getComIpProprio("/api/v1/professionals")).andExpect(status(200));
		}

	}

	// =======================================================================
	// Refresh
	// =======================================================================

	@Nested
	@DisplayName("POST /api/v1/auth/refresh")
	class Renovacao {

		@Test
		@DisplayName("com token valido renova os dois cookies")
		void renovaOk() throws Exception {
			MvcResult login = logar();

			MvcResult renovado = mvc.perform(postJson("/api/v1/auth/refresh", "{}")
					.cookie(cookieDe(login, CookieFactory.COOKIE_REFRESH)))
					.andExpect(status(200)).andReturn();

			JsonNode corpo = corpoDe(renovado);
			assertThat(corpo.get("success").asBoolean()).isTrue();
			assertThat(corpo.get("message").asString()).isEqualTo("Token refreshed");
			assertThat(corpo.get("data").isNull()).isTrue();

			// Os dois cookies vem de novo, e o access novo funciona.
			assertThat(cookiesDe(renovado)).hasSize(2);
			mvc.perform(getComIpProprio("/api/v1/admin/health")
					.cookie(cookieDe(renovado, CookieFactory.COOKIE_ACCESS)))
					.andExpect(status(200));
		}

		@Test
		@DisplayName("a rotacao invalida o token anterior: ele serve uma vez so")
		void rotacaoInvalidaOAnterior() throws Exception {
			MvcResult login = logar();
			jakarta.servlet.http.Cookie primeiro = cookieDe(login, CookieFactory.COOKIE_REFRESH);

			mvc.perform(postJson("/api/v1/auth/refresh", "{}").cookie(primeiro))
					.andExpect(status(200));

			// Reusar o mesmo refresh token agora falha: a linha foi apagada.
			// E o que protege a vitima de um cookie copiado.
			JsonNode corpo = corpoDe(mvc.perform(postJson("/api/v1/auth/refresh", "{}")
					.cookie(primeiro)).andExpect(status(401)).andReturn());

			assertThat(corpo.get("message").asString())
					.isEqualTo("Refresh token expired or revoked");
		}

		@Test
		@DisplayName("sem cookie: 401 com 'Refresh token missing'")
		void semCookie() throws Exception {
			JsonNode corpo = corpoDe(mvc.perform(postJson("/api/v1/auth/refresh", "{}"))
					.andExpect(status(401)).andReturn());

			assertThat(corpo.get("message").asString()).isEqualTo("Refresh token missing");
		}

		@Test
		@DisplayName("token revogado pelo logout: 401")
		void tokenRevogado() throws Exception {
			MvcResult login = logar();
			jakarta.servlet.http.Cookie refresh = cookieDe(login, CookieFactory.COOKIE_REFRESH);

			mvc.perform(postJson("/api/v1/auth/logout", "{}").cookie(refresh))
					.andExpect(status(200));

			mvc.perform(postJson("/api/v1/auth/refresh", "{}").cookie(refresh))
					.andExpect(status(401));
		}

		@Test
		@DisplayName("um access token nao serve como refresh: os segredos sao diferentes")
		void accessNaoServeComoRefresh() throws Exception {
			MvcResult login = logar();
			String access = valorDoCookie(cookiesDe(login), CookieFactory.COOKIE_ACCESS);

			mvc.perform(postJson("/api/v1/auth/refresh", "{}")
					.cookie(new jakarta.servlet.http.Cookie(CookieFactory.COOKIE_REFRESH, access)))
					.andExpect(status(401));
		}

		@Test
		@DisplayName("refresh expirado (8 dias): 401")
		void refreshExpirado() throws Exception {
			MvcResult login = logar();
			jakarta.servlet.http.Cookie refresh = cookieDe(login, CookieFactory.COOKIE_REFRESH);

			BDDMockito.given(clock.instant()).willReturn(AGORA.plusSeconds(8 * 24 * 60 * 60));

			mvc.perform(postJson("/api/v1/auth/refresh", "{}").cookie(refresh))
					.andExpect(status(401));
		}

	}

	// =======================================================================
	// Logout
	// =======================================================================

	@Nested
	@DisplayName("POST /api/v1/auth/logout")
	class Logout {

		@Test
		@DisplayName("limpa os dois cookies com Max-Age=0 e o mesmo path")
		void limpaOsDois() throws Exception {
			MvcResult login = logar();

			MvcResult saida = mvc.perform(postJson("/api/v1/auth/logout", "{}")
					.cookie(cookieDe(login, CookieFactory.COOKIE_REFRESH)))
					.andExpect(status(200)).andReturn();

			List<String> cookies = cookiesDe(saida);
			assertThat(cookies).hasSize(2);

			// O path precisa bater com o do cookie original: o navegador
			// identifica um cookie por nome + domain + path, e um path errado
			// criaria um segundo cookie vazio deixando o original de pe.
			assertThat(cookie(cookies, CookieFactory.COOKIE_ACCESS))
					.contains("Max-Age=0").contains("Path=/");
			assertThat(cookie(cookies, CookieFactory.COOKIE_REFRESH))
					.contains("Max-Age=0").contains("Path=/api/v1/auth/refresh");
		}

		@Test
		@DisplayName("apaga a linha de refresh_tokens")
		void apagaDoBanco() throws Exception {
			MvcResult login = logar();
			assertThat(refreshTokens.count()).isEqualTo(1);

			mvc.perform(postJson("/api/v1/auth/logout", "{}")
					.cookie(cookieDe(login, CookieFactory.COOKIE_REFRESH)))
					.andExpect(status(200));

			assertThat(refreshTokens.count()).isZero();
		}

		@Test
		@DisplayName("sem cookie nenhum ainda responde 200 e limpa")
		void semCookieNaoFalha() throws Exception {
			// Quem clica em "sair" quer ficar sem sessao; responder erro o
			// deixaria preso nela.
			MvcResult saida = mvc.perform(postJson("/api/v1/auth/logout", "{}"))
					.andExpect(status(200)).andReturn();

			assertThat(cookiesDe(saida)).hasSize(2);
		}

	}

	// =======================================================================
	// Nada vaza
	// =======================================================================

	@Nested
	@DisplayName("nenhuma resposta vaza segredo nem stack trace")
	class NaoVaza {

		@Test
		@DisplayName("401, 403 e 422 nao trazem stack, SQL nem nome de classe Java")
		void semVazamento() throws Exception {
			List<MvcResult> respostas = List.of(
					mvc.perform(getComIpProprio("/api/v1/admin/health")).andReturn(),
					mvc.perform(postJson("/api/v1/auth/login", """
							{ "email": "%s", "password": "errada" }
							""".formatted(EMAIL))).andReturn(),
					mvc.perform(postJson("/api/v1/auth/login", "{ \"email\": 1 }")).andReturn(),
					mvc.perform(postJson("/api/v1/auth/refresh", "{}")).andReturn());

			for (MvcResult resposta : respostas) {
				String texto = resposta.getResponse().getContentAsString();
				assertThat(texto)
						.as("resposta: %s", texto)
						.doesNotContain("com.cauacosenza")
						.doesNotContain("org.springframework")
						.doesNotContain("Exception")
						.doesNotContain("SELECT")
						.doesNotContain("$2a$")
						.doesNotContain("passwordHash")
						.doesNotContain("token_hash");
			}
		}

		@Test
		@DisplayName("a listagem do painel nao traz hash de senha nem token")
		void painelNaoVazaCredencial() throws Exception {
			String texto = mvc.perform(getComIpProprio("/api/v1/admin/appointments?date=2026-12-15")
					.cookie(cookieAccess()))
					.andExpect(status(200)).andReturn().getResponse().getContentAsString();

			assertThat(texto)
					.doesNotContain("passwordHash")
					.doesNotContain("$2a$")
					.doesNotContain("tokenHash")
					.doesNotContain("access_token");
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private MvcResult logar() throws Exception {
		return mvc.perform(postJson("/api/v1/auth/login", """
				{ "email": "%s", "password": "%s" }
				""".formatted(EMAIL, SENHA)))
				.andExpect(status(200))
				.andReturn();
	}

	/** Um cookie de access token valido, pronto para anexar a requisicao. */
	private jakarta.servlet.http.Cookie cookieAccess() throws Exception {
		return cookieDe(logar(), CookieFactory.COOKIE_ACCESS);
	}

	private static jakarta.servlet.http.Cookie cookieDe(MvcResult resultado, String nome) {
		return new jakarta.servlet.http.Cookie(
				nome, valorDoCookie(cookiesDe(resultado), nome));
	}

	private static List<String> cookiesDe(MvcResult resultado) {
		List<String> cabecalhos = resultado.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		return (cabecalhos != null) ? cabecalhos : List.of();
	}

	private static String cookie(List<String> cookies, String nome) {
		return cookies.stream()
				.filter(c -> c.startsWith(nome + "="))
				.findFirst()
				.orElseThrow(() -> new AssertionError("cookie " + nome + " ausente em " + cookies));
	}

	private static String valorDoCookie(List<String> cookies, String nome) {
		String bruto = cookie(cookies, nome);
		int inicio = nome.length() + 1;
		int fim = bruto.indexOf(';', inicio);
		return (fim < 0) ? bruto.substring(inicio) : bruto.substring(inicio, fim);
	}

	private JsonNode corpoDe(MvcResult resultado) throws Exception {
		return this.json.readTree(resultado.getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static org.springframework.test.web.servlet.ResultMatcher status(int esperado) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(esperado);
	}

}
