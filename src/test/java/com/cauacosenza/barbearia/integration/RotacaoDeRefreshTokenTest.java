package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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
import com.cauacosenza.barbearia.domain.entity.RefreshToken;
import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.repository.AdminUserRepository;
import com.cauacosenza.barbearia.repository.RefreshTokenRepository;
import com.cauacosenza.barbearia.security.CookieFactory;
import com.cauacosenza.barbearia.security.JwtService;

import tools.jackson.databind.JsonNode;

/**
 * Os caminhos de recusa do {@code POST /auth/refresh}.
 *
 * <p><b>De onde isto vem.</b> A medicao por mutante apontou quatro
 * condicionais sobrevivendo dentro de {@code AuthService.renovar} — rotacao de
 * refresh token, que ja quebrou em silencio uma vez nesta migracao, quando os
 * tokens saiam byte a byte identicos dentro do mesmo segundo por falta de
 * {@code jti}. O {@code AuthIntegrationTest} cobre o caminho feliz e a
 * rotacao; o que faltava era <b>o caminho do atacante</b>, em que o caminho
 * feliz continua funcionando identico e so a recusa muda.
 *
 * <p>Cada teste aqui existe para um mutante especifico, e o javadoc de cada um
 * diz qual. Nao sao variacoes de "token ruim da 401": sao tres razoes
 * diferentes de recusar, que no codigo sao tres {@code if} independentes.
 */
class RotacaoDeRefreshTokenTest extends AbstractPostgresIntegrationTest {

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

	@Autowired
	private JwtService jwt;

	private AdminUser admin;

	@BeforeEach
	void criarAdmin() {
		BDDMockito.given(this.clock.instant()).willReturn(AGORA);
		BDDMockito.given(this.clock.getZone()).willReturn(ZoneOffset.UTC);

		AdminUser usuario = new AdminUser();
		usuario.setEmail(EMAIL);
		usuario.setPasswordHash(this.senhas.encode(SENHA));
		usuario.setName("Jhonatan Correa");
		usuario.setPhone("5522999990000");
		usuario.setRole(Role.ADMIN);
		this.admin = this.usuarios.saveAndFlush(usuario);
	}

	/**
	 * Cookie <b>vazio</b>, nao ausente.
	 *
	 * <p>Mata o mutante que troca {@code refreshToken.isBlank()} por
	 * {@code false} na guarda de entrada. O {@code semCookie} do
	 * {@code AuthIntegrationTest} nao alcanca esse: sem cookie o valor chega
	 * {@code null} e o primeiro ramo do {@code ||} ja recusa. Para exercitar o
	 * segundo ramo o cookie precisa <b>existir e estar vazio</b>.
	 *
	 * <p>E e um caminho real, nao hipotetico: o
	 * {@link CookieFactory#limparRefreshToken()} do logout grava exatamente
	 * {@code refresh_token=} com {@code Max-Age=0}. Cliente que reenvie esse
	 * cookie cai aqui.
	 */
	@Test
	@DisplayName("cookie de refresh vazio: 401 'Refresh token missing'")
	void cookieVazioEhTokenAusente() throws Exception {
		JsonNode corpo = corpoDe(this.mvc.perform(postJson("/api/v1/auth/refresh", "{}")
				.cookie(new jakarta.servlet.http.Cookie(CookieFactory.COOKIE_REFRESH, ""))));

		assertThat(corpo.get("message").asString())
				.as("""
						cookie vazio e ausencia de sessao, nao sessao ruim: a \
						mensagem precisa ser a de token ausente, que e a que o \
						frontend usa para mandar para o login sem tentar \
						renovar""")
				.isEqualTo("Refresh token missing");
	}

	/**
	 * O hash estar na tabela nao substitui verificar a assinatura.
	 *
	 * <p>Mata o mutante que troca {@code userId == null} por {@code false},
	 * removendo a checagem do JWT. Com ele, a autenticacao passaria a depender
	 * <b>so</b> de o SHA-256 do que foi enviado estar em
	 * {@code refresh_tokens} — a tabela deixaria de ser lista de revogacao e
	 * viraria a propria credencial.
	 *
	 * <p>O teste grava uma linha cujo {@code token_hash} e o hash de uma
	 * string que <b>nao e um JWT</b>, e manda essa string no cookie. Sem o
	 * mutante: 401, porque a validacao do token falha antes de qualquer
	 * consulta. Com o mutante: o {@code userId} vem nulo, a linha e
	 * encontrada, e o {@code findById(null)} estoura — 401 vira 500.
	 */
	@Test
	@DisplayName("hash na tabela sem assinatura valida: 401, nunca 500")
	void hashNaTabelaNaoDispensaAssinatura() throws Exception {
		String naoEhJwt = "nao-sou-um-jwt-mas-meu-hash-esta-na-tabela";

		RefreshToken linha = new RefreshToken();
		linha.setUser(this.admin);
		linha.setTokenHash(this.jwt.hashDeArmazenamento(naoEhJwt));
		linha.setExpiresAt(AGORA.plus(JwtService.VALIDADE_REFRESH));
		this.refreshTokens.saveAndFlush(linha);

		MvcResult resultado = this.mvc.perform(postJson("/api/v1/auth/refresh", "{}")
				.cookie(new jakarta.servlet.http.Cookie(
						CookieFactory.COOKIE_REFRESH, naoEhJwt)))
				.andReturn();

		assertThat(resultado.getResponse().getStatus())
				.as("""
						a assinatura tem que ser conferida ANTES de a tabela \
						ser consultada; um 500 aqui significaria que o codigo \
						seguiu com userId nulo""")
				.isEqualTo(401);
		assertThat(corpoDe(resultado).get("message").asString())
				.isEqualTo("Refresh token expired or revoked");

		// E nenhuma sessao sai desse caminho.
		assertThat(resultado.getResponse().getHeaders("Set-Cookie")).isEmpty();
	}

	/**
	 * A linha vencida e a unica expiracao que o servidor controla.
	 *
	 * <p>Mata o mutante que troca
	 * {@code armazenado.getExpiresAt().isBefore(agora)} por {@code false}.
	 * <b>E o mais grave dos quatro:</b> com ele, um refresh token vencido
	 * continua renovando a sessao indefinidamente. O {@code exp} dentro do
	 * JWT tem sete dias e nao se pode encurtar depois de emitido; a coluna
	 * {@code expires_at} e o que permite encerrar a sessao do lado do
	 * servidor. Deixar de le-la remove essa capacidade inteira.
	 *
	 * <p>O cenario e montado envelhecendo a linha por SQL, e nao esperando:
	 * o token e legitimo, recem-emitido por um login de verdade, e so a linha
	 * esta vencida — exatamente o estado em que o servidor precisa dizer nao.
	 */
	@Test
	@DisplayName("linha vencida no banco: 401, mesmo com o JWT ainda valido")
	void linhaVencidaRecusaMesmoComJwtValido() throws Exception {
		MvcResult login = this.mvc.perform(postJson("/api/v1/auth/login", """
				{"email": "%s", "password": "%s"}
				""".formatted(EMAIL, SENHA))).andReturn();
		jakarta.servlet.http.Cookie refresh =
				login.getResponse().getCookie(CookieFactory.COOKIE_REFRESH);
		assertThat(refresh).isNotNull();

		// O JWT continua com sete dias de validade; so a linha envelhece.
		int linhas = this.jdbc.update(
				"UPDATE \"refresh_tokens\" SET \"expires_at\" = ?",
				java.sql.Timestamp.from(AGORA.minus(1, ChronoUnit.DAYS)));
		assertThat(linhas).as("o login deveria ter gravado uma linha").isEqualTo(1);

		MvcResult resultado = this.mvc.perform(postJson("/api/v1/auth/refresh", "{}")
				.cookie(refresh)).andReturn();

		assertThat(resultado.getResponse().getStatus())
				.as("""
						refresh token com a linha vencida nao pode renovar: a \
						coluna expires_at e a unica forma de o servidor \
						encerrar uma sessao antes dos sete dias do JWT""")
				.isEqualTo(401);
		assertThat(corpoDe(resultado).get("message").asString())
				.isEqualTo("Refresh token expired or revoked");

		// Nao emitiu par novo -- se tivesse, a sessao vencida teria se
		// renovado e a expiracao nunca chegaria.
		assertThat(resultado.getResponse().getHeaders("Set-Cookie")).isEmpty();

		// E a linha vencida continua lá: a recusa nao e um delete disfarcado.
		assertThat(this.refreshTokens.count()).isEqualTo(1);
	}

	// =======================================================================

	private JsonNode corpoDe(org.springframework.test.web.servlet.ResultActions resultado)
			throws Exception {

		return corpoDe(resultado.andReturn());
	}

	private JsonNode corpoDe(MvcResult resultado) throws Exception {
		return this.json.readTree(resultado.getResponse()
				.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}

}
