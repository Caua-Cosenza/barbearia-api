package com.cauacosenza.barbearia.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cauacosenza.barbearia.domain.entity.AdminUser;
import com.cauacosenza.barbearia.domain.enums.Role;

/**
 * Emite e verifica os dois tokens, no mesmo formato que o backend Node.
 *
 * <h2>Dois tokens, dois segredos, dois conteudos</h2>
 *
 * <table border="1">
 *   <caption>O que cada token carrega</caption>
 *   <tr><th></th><th>access</th><th>refresh</th></tr>
 *   <tr><td>segredo</td><td>{@code JWT_SECRET}</td><td>{@code JWT_REFRESH_SECRET}</td></tr>
 *   <tr><td>claims</td><td>{@code sub} + {@code role}</td><td>somente {@code sub}</td></tr>
 *   <tr><td>validade</td><td>15 minutos</td><td>7 dias</td></tr>
 * </table>
 *
 * <p>Os segredos sao <b>diferentes de proposito</b>: um access token vazado nao
 * pode ser transformado em refresh token, e vice-versa. E o refresh nao carrega
 * {@code role} porque nao autoriza nada — ele so prova "sou o usuario X" para
 * quem vai emitir um access novo, e o papel e relido do banco nessa hora. Um
 * usuario rebaixado de ADMIN para CLIENT perde o acesso no proximo refresh, em
 * vez de carregar o papel antigo por sete dias.
 *
 * <h2>Compatibilidade com o {@code @fastify/jwt}</h2>
 *
 * <p>HS256 sobre os <b>bytes UTF-8 do segredo</b>, que e o que a biblioteca
 * {@code jsonwebtoken} do Node usa quando recebe o segredo como string. Isso
 * torna os tokens intercambiaveis entre os dois backends — util durante o
 * corte, quando as duas aplicacoes podem conviver atras do mesmo dominio.
 *
 * <h2>O hash do refresh token</h2>
 *
 * <p>{@link #hashDeArmazenamento(String)} e <b>SHA-256 em hexadecimal</b>, nao
 * bcrypt. Confirmado no {@code authService.ts}
 * ({@code createHash('sha256').update(token).digest('hex')}), e nao e escolha
 * arbitraria: a coluna {@code refresh_tokens.token_hash} tem indice unico e a
 * validacao e uma busca exata por esse hash. Bcrypt tem salt aleatorio, produz
 * hash diferente a cada chamada e tornaria a busca impossivel — seria preciso
 * varrer a tabela comparando um a um. SHA-256 serve aqui porque a entrada e um
 * JWT de alta entropia, nao uma senha: nao ha dicionario para atacar.
 */
@Component
public class JwtService {

	/** {@code JWT_ACCESS_EXPIRES} do Node, cujo default e {@code 15m}. */
	public static final Duration VALIDADE_ACCESS = Duration.ofMinutes(15);

	/** {@code JWT_REFRESH_EXPIRES} do Node, cujo default e {@code 7d}. */
	public static final Duration VALIDADE_REFRESH = Duration.ofDays(7);

	private static final String CLAIM_ROLE = "role";

	private static final HexFormat HEX = HexFormat.of();

	private final SecretKey chaveAccess;

	private final SecretKey chaveRefresh;

	private final Clock clock;

	public JwtService(
			@Value("${JWT_SECRET}") String segredoAccess,
			@Value("${JWT_REFRESH_SECRET}") String segredoRefresh,
			Clock clock) {

		this.chaveAccess = chaveDe(segredoAccess, "JWT_SECRET");
		this.chaveRefresh = chaveDe(segredoRefresh, "JWT_REFRESH_SECRET");
		this.clock = clock;
	}

	/** Access token: {@code sub} = id do usuario, {@code role} = papel. */
	public String emitirAccessToken(AdminUser usuario) {
		Instant agora = Instant.now(this.clock);
		return Jwts.builder()
				.subject(usuario.getId())
				.claim(CLAIM_ROLE, usuario.getRole().name())
				.issuedAt(Date.from(agora))
				.expiration(Date.from(agora.plus(VALIDADE_ACCESS)))
				.signWith(this.chaveAccess)
				.compact();
	}

	/**
	 * Refresh token: {@code sub}, um {@code jti} unico, e o outro segredo.
	 *
	 * <p><b>O {@code jti} e um acrescimo em relacao ao Node</b>, e conserta um
	 * furo silencioso. O {@code iat} de um JWT tem resolucao de <i>segundos</i>:
	 * sem um identificador proprio, dois refresh tokens emitidos para o mesmo
	 * usuario no mesmo segundo sao <b>byte a byte identicos</b> — mesmo
	 * {@code sub}, mesmo {@code iat}, mesmo {@code exp}, e HS256 e
	 * deterministico.
	 *
	 * <p>Isso quebra a rotacao justamente quando ela importa: o token antigo e
	 * apagado e o novo e gravado com o mesmo hash, entao o token que deveria
	 * ter morrido continua valendo. E faz um logout revogar a sessao que um
	 * login concorrente acabou de abrir.
	 *
	 * <p>O token e opaco para o cliente e o claim nao aparece em lugar nenhum
	 * do contrato, entao o acrescimo e invisivel ao frontend. Registrado no
	 * README.
	 */
	public String emitirRefreshToken(AdminUser usuario) {
		Instant agora = Instant.now(this.clock);
		return Jwts.builder()
				.subject(usuario.getId())
				.id(UUID.randomUUID().toString())
				.issuedAt(Date.from(agora))
				.expiration(Date.from(agora.plus(VALIDADE_REFRESH)))
				.signWith(this.chaveRefresh)
				.compact();
	}

	/**
	 * Valida um access token e devolve quem ele identifica.
	 *
	 * @return o dono do token, ou {@code null} se a assinatura, o formato ou a
	 *     validade estiverem errados. Devolve nulo em vez de lancar porque o
	 *     filtro de autenticacao trata todos esses casos da mesma forma —
	 *     "nao autenticado" — e um {@code try/catch} por motivo nao mudaria a
	 *     resposta, so daria ao atacante um oraculo sobre <i>por que</i> o
	 *     token foi recusado.
	 */
	public UsuarioAutenticado validarAccessToken(String token) {
		Claims claims = ler(token, this.chaveAccess);
		if (claims == null) {
			return null;
		}
		String papel = claims.get(CLAIM_ROLE, String.class);
		if (papel == null) {
			return null;
		}
		try {
			return new UsuarioAutenticado(claims.getSubject(), Role.valueOf(papel));
		}
		catch (IllegalArgumentException ex) {
			// Papel que nao existe mais no enum: token de uma versao antiga.
			return null;
		}
	}

	/**
	 * Valida um refresh token e devolve o id do usuario.
	 *
	 * <p>Assinatura valida <b>nao basta</b> para renovar a sessao: o
	 * {@code AuthService} ainda confere se o hash do token esta na tabela
	 * {@code refresh_tokens}. E isso que faz o logout e a rotacao terem efeito
	 * — um JWT e valido ate expirar, e sem a lista do banco um token roubado
	 * valeria sete dias mesmo depois de o dono sair.
	 *
	 * @return o {@code sub}, ou {@code null} se o token nao for valido
	 */
	public String validarRefreshToken(String token) {
		Claims claims = ler(token, this.chaveRefresh);
		return (claims == null) ? null : claims.getSubject();
	}

	/**
	 * O que vai para a coluna {@code refresh_tokens.token_hash}: SHA-256 do
	 * token, em hexadecimal minusculo. Ver o javadoc da classe.
	 */
	public String hashDeArmazenamento(String token) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HEX.formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 e obrigatorio em toda JVM; se faltar, nada aqui funciona.
			throw new IllegalStateException("SHA-256 indisponivel nesta JVM", ex);
		}
	}

	private Claims ler(String token, SecretKey chave) {
		if (token == null || token.isBlank()) {
			return null;
		}
		try {
			return Jwts.parser()
					.verifyWith(chave)
					// O mesmo relogio do resto da aplicacao: sem isso o teste
					// de token expirado dependeria de esperar de verdade.
					.clock(() -> Date.from(Instant.now(this.clock)))
					.build()
					.parseSignedClaims(token)
					.getPayload();
		}
		catch (JwtException | IllegalArgumentException ex) {
			return null;
		}
	}

	private static SecretKey chaveDe(String segredo, String nomeDaVariavel) {
		if (segredo == null || segredo.isBlank()) {
			throw new IllegalStateException(nomeDaVariavel + " nao definida");
		}
		byte[] bytes = segredo.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			// HS256 exige 256 bits. O EnvironmentValidator ja cobra 32
			// caracteres no boot; esta checagem protege o uso direto em teste.
			throw new IllegalStateException(
					nomeDaVariavel + " precisa de no minimo 32 bytes para HS256");
		}
		return Keys.hmacShaKeyFor(bytes);
	}

	/**
	 * Quem o access token identifica.
	 *
	 * @param userId o {@code sub}
	 * @param role o papel, usado para montar a autoridade do Spring Security
	 */
	public record UsuarioAutenticado(String userId, Role role) {

	}

}
