package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cauacosenza.barbearia.domain.entity.AdminUser;
import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.repository.AdminUserRepository;
import com.cauacosenza.barbearia.security.CookieFactory;

/**
 * {@code POST /api/v1/auth/register} nao existe mais — e a vizinhanca ficou.
 *
 * <p><b>Por que um teste para algo que foi apagado.</b> Codigo removido nao
 * deixa rastro: ninguem percebe se a rota voltar num merge, numa reversao
 * parcial ou porque alguem copiou o {@code routes/auth.ts} do Node de novo
 * procurando equivalencia. A rota <b>era</b> escrita nao autenticada numa
 * tabela, criando conta de um tipo que o sistema nao consome — o agendamento
 * publico nao tem conta. A ausencia e a decisao; este teste e a ancora dela.
 *
 * <p><b>E 401, nao 404.</b> Nao da para pedir 404 ou 405 aqui, e a razao e
 * estrutural: o {@code SecurityConfig} termina em
 * {@code anyRequest().denyAll()}, e a cadeia do Spring Security roda
 * <b>antes</b> do {@code DispatcherServlet}. Qualquer caminho que nao esteja
 * explicitamente liberado e recusado com 401 sem que ninguem chegue a
 * perguntar se existe um handler. Tirar a linha de {@code permitAll()} do
 * {@code /register} nao o transformou em "rota inexistente", e sim em
 * "caminho nao liberado" — e no desenho desta aplicacao os dois sao a mesma
 * coisa, de proposito.
 *
 * <p>Por isso a ancora aqui e mais forte do que um numero: o
 * {@code /register} tem que responder <b>exatamente igual</b> a um caminho
 * que nunca existiu. Enquanto as duas respostas forem indistinguiveis, a
 * rota esta ausente de verdade — e nem a existencia dela e observavel de
 * fora.
 *
 * <p>Os dois ultimos testes existem porque remover um metodo de um
 * {@code @RestController} e mexer no {@code SecurityConfig} tem vizinhos:
 * {@code /auth/login} e {@code /auth/logout} saem do mesmo controller e das
 * linhas de {@code permitAll()} imediatamente ao lado da que foi apagada.
 */
class RegistroFoiRemovidoTest extends AbstractPostgresIntegrationTest {

	private static final String EMAIL = "admin@barbearia.com";

	private static final String SENHA = "Admin@123456";

	/** 2026-12-15, 03:00 em Brasilia. */
	private static final Instant AGORA = Instant.parse("2026-12-15T06:00:00Z");

	/** O corpo que a rota aceitava, para que a recusa nao seja de validacao. */
	private static final String CORPO = """
			{
			  "name": "Pessoa Qualquer",
			  "email": "qualquer.um@exemplo.com.br",
			  "phone": "5522999990000",
			  "password": "Senha@123456",
			  "confirmPassword": "Senha@123456"
			}
			""";

	@MockitoBean(name = "clock")
	private Clock clock;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdminUserRepository usuarios;

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

	@Test
	@DisplayName("POST /auth/register responde igual a um caminho que nunca existiu")
	void registroEhIndistinguivelDeRotaInexistente() throws Exception {
		MvcResult removida = this.mvc.perform(postJson("/api/v1/auth/register", CORPO))
				.andReturn();
		MvcResult nuncaExistiu = this.mvc.perform(
				postJson("/api/v1/auth/rota-que-nunca-existiu", CORPO)).andReturn();

		assertThat(removida.getResponse().getStatus())
				.as("""
						a rota foi removida de proposito: nada a chamava, e ela \
						era escrita nao autenticada em admin_users. Ela tem que \
						responder exatamente como um caminho inexistente -- se \
						divergir, alguem a trouxe de volta, e a pergunta a \
						fazer e por que""")
				.isEqualTo(nuncaExistiu.getResponse().getStatus());

		assertThat(corpoDe(removida))
				.as("nem o corpo pode revelar que este caminho ja significou algo")
				.isEqualTo(corpoDe(nuncaExistiu));

		// E o que as duas respondem e 401, do anyRequest().denyAll(): a
		// cadeia de seguranca recusa antes de o roteamento existir. Fixado
		// aqui para que trocar o denyAll por um 404 generico seja uma
		// decisao, e nao um efeito colateral.
		assertThat(removida.getResponse().getStatus()).isEqualTo(401);

		// Nenhuma conta foi criada no caminho: so o admin do @BeforeEach.
		assertThat(this.usuarios.count())
				.as("a requisicao nao pode ter criado conta nenhuma")
				.isEqualTo(1);
	}

	/**
	 * O corpo da resposta, com o {@code timestamp} normalizado.
	 *
	 * <p>O envelope carrega a hora em que a resposta saiu, e as duas
	 * requisicoes nao saem no mesmo milissegundo. Comparar o resto e o ponto:
	 * {@code success}, {@code error} e {@code message} tem que ser identicos.
	 */
	private static String corpoDe(MvcResult resultado) throws Exception {
		return resultado.getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
				.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<hora>\"");
	}

	@Test
	@DisplayName("login continua funcionando, com os dois cookies")
	void loginIntacto() throws Exception {
		MvcResult login = this.mvc.perform(postJson("/api/v1/auth/login", """
				{"email": "%s", "password": "%s"}
				""".formatted(EMAIL, SENHA))).andReturn();

		assertThat(login.getResponse().getStatus()).isEqualTo(200);
		assertThat(login.getResponse().getCookie(CookieFactory.COOKIE_ACCESS)).isNotNull();
		assertThat(login.getResponse().getCookie(CookieFactory.COOKIE_REFRESH)).isNotNull();

		// E a sessao vale para o que ela deveria valer.
		this.mvc.perform(getComIpProprio("/api/v1/admin/health")
				.cookie(login.getResponse().getCookie(CookieFactory.COOKIE_ACCESS)))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.status().isOk());
	}

	@Test
	@DisplayName("logout continua funcionando, e apaga os dois cookies")
	void logoutIntacto() throws Exception {
		MvcResult login = this.mvc.perform(postJson("/api/v1/auth/login", """
				{"email": "%s", "password": "%s"}
				""".formatted(EMAIL, SENHA))).andReturn();

		MvcResult logout = this.mvc.perform(postJson("/api/v1/auth/logout", "{}")
				.cookie(login.getResponse().getCookie(CookieFactory.COOKIE_ACCESS),
						login.getResponse().getCookie(CookieFactory.COOKIE_REFRESH)))
				.andReturn();

		assertThat(logout.getResponse().getStatus()).isEqualTo(200);
		// Max-Age zero e o que manda o navegador descartar.
		assertThat(logout.getResponse().getHeaders("Set-Cookie"))
				.hasSize(2)
				.allMatch(cabecalho -> cabecalho.contains("Max-Age=0"));
	}

}
