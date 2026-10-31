package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cauacosenza.barbearia.domain.entity.AdminUser;
import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.repository.AdminUserRepository;
import com.cauacosenza.barbearia.security.CookieFactory;

import tools.jackson.databind.JsonNode;

/**
 * O que {@code POST /api/v1/auth/register} realmente faz, sem credencial.
 *
 * <p><b>Por que este teste existe.</b> A medicao por mutante mostrou
 * {@code AuthService.registrar} com <b>sete mutantes NO_COVERAGE</b> — nenhum
 * teste tocava o metodo. E o {@code SecurityConfig} declara a rota
 * {@code permitAll()}. Metodo sem teste nenhum numa rota aberta e a
 * combinacao que merece ser medida antes de qualquer outra coisa, e medida
 * pelo comportamento, nao pela leitura do codigo.
 *
 * <p><b>O veredito, para quem ler isto depois:</b> a rota responde <b>201 sem
 * autenticacao</b>, entao qualquer pessoa na internet cria conta. A conta
 * nasce {@link Role#CLIENT}, e {@code CLIENT} nao alcanca {@code /admin} —
 * isto <b>nao</b> e criacao aberta de conta de administrador. O papel esta
 * fixado em tres camadas independentes, e os dois ultimos testes daqui
 * existem para que continue assim.
 */
class RegistroAbertoTest extends AbstractPostgresIntegrationTest {

	private static final String EMAIL = "qualquer.um@exemplo.com.br";

	private static final String SENHA = "Senha@123456";

	/** 2026-12-15, 03:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdminUserRepository usuarios;

	@BeforeEach
	void fixarRelogio() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);
	}

	@Test
	@DisplayName("sem credencial nenhuma, a rota cria a conta e responde 201")
	void registroEhAberto() throws Exception {
		// Nenhum cookie, nenhum header de autenticacao: so o corpo.
		MvcResult resultado = this.mvc.perform(
				postJson("/api/v1/auth/register", corpo(EMAIL))).andReturn();

		assertThat(resultado.getResponse().getStatus())
				.as("""
						a rota e permitAll() no SecurityConfig; se algum dia \
						passar a exigir autenticacao, este numero muda e o \
						teste tem que ser relido, nao ajustado""")
				.isEqualTo(201);

		JsonNode corpo = this.json.readTree(resultado.getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(corpo.get("success").asBoolean()).isTrue();
		assertThat(corpo.get("data").get("email").asString()).isEqualTo(EMAIL);
		assertThat(corpo.get("data").get("id").asString()).isNotBlank();

		// Nao emite sessao: o Node responde 201 com id e e-mail, e quem se
		// cadastra faz login em seguida. Um cookie aqui seria sessao criada
		// por quem nunca provou nada.
		List<String> cookies = resultado.getResponse().getHeaders("Set-Cookie");
		assertThat(cookies)
				.as("registro nao pode abrir sessao")
				.noneMatch(cabecalho -> cabecalho.startsWith(CookieFactory.COOKIE_ACCESS + "=")
						|| cabecalho.startsWith(CookieFactory.COOKIE_REFRESH + "="));
	}

	@Test
	@DisplayName("a conta criada nasce CLIENT, nunca ADMIN")
	void contaNasceClient() throws Exception {
		this.mvc.perform(postJson("/api/v1/auth/register", corpo(EMAIL)));

		AdminUser criado = this.usuarios.findByEmail(EMAIL).orElseThrow();

		// O papel esta fixado em tres camadas independentes: o
		// setRole(Role.CLIENT) do AuthService, o inicializador do campo em
		// AdminUser, e o DEFAULT 'CLIENT' da coluna na V1. Esta asercao
		// cobre o resultado das tres.
		assertThat(criado.getRole())
				.as("conta criada sem autenticacao nunca pode nascer com acesso a /admin")
				.isEqualTo(Role.CLIENT);
	}

	@Test
	@DisplayName("logado com a conta aberta, /admin devolve 403")
	void contaAbertaNaoAlcancaAdmin() throws Exception {
		this.mvc.perform(postJson("/api/v1/auth/register", corpo(EMAIL)));

		// O login funciona -- a conta e legitima, so nao e privilegiada.
		MvcResult login = this.mvc.perform(postJson("/api/v1/auth/login", """
				{"email": "%s", "password": "%s"}
				""".formatted(EMAIL, SENHA))).andReturn();
		assertThat(login.getResponse().getStatus()).isEqualTo(200);

		jakarta.servlet.http.Cookie acesso = cookieDe(login, CookieFactory.COOKIE_ACCESS);
		assertThat(acesso).as("o login deveria ter devolvido o cookie de acesso").isNotNull();

		// 403 e nao 401: a sessao e valida, o papel e que nao basta. A
		// diferenca importa -- 401 faria o frontend tentar renovar a sessao
		// num loop.
		this.mvc.perform(getComIpProprio("/api/v1/admin/health").cookie(acesso))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.status().isForbidden());
	}

	// =======================================================================

	private static String corpo(String email) {
		return """
				{
				  "name": "Pessoa Qualquer",
				  "email": "%s",
				  "phone": "5522999990000",
				  "password": "%s",
				  "confirmPassword": "%s"
				}
				""".formatted(email, SENHA, SENHA);
	}

	private static jakarta.servlet.http.Cookie cookieDe(MvcResult resultado, String nome) {
		return resultado.getResponse().getCookie(nome);
	}

}
