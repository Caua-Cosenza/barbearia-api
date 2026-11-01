package com.cauacosenza.barbearia.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.AdminUser;
import com.cauacosenza.barbearia.domain.entity.RefreshToken;
import com.cauacosenza.barbearia.domain.exception.CredenciaisInvalidasException;
import com.cauacosenza.barbearia.domain.exception.SessaoInvalidaException;
import com.cauacosenza.barbearia.dto.request.LoginRequest;
import com.cauacosenza.barbearia.dto.response.LoginResponse;
import com.cauacosenza.barbearia.repository.AdminUserRepository;
import com.cauacosenza.barbearia.repository.RefreshTokenRepository;
import com.cauacosenza.barbearia.security.JwtService;

/**
 * Login, refresh e logout.
 *
 * <h2>O par de tokens</h2>
 *
 * <p>O access token vive 15 minutos e autoriza tudo; o refresh vive sete dias
 * e serve apenas para pedir um access novo. A divisao limita o estrago de um
 * token vazado e e o que permite revogar uma sessao: um JWT nao pode ser
 * desfeito, mas o refresh so funciona se o seu hash estiver em
 * {@code refresh_tokens}, e essa linha pode ser apagada.
 *
 * <h2>Rotacao a cada refresh</h2>
 *
 * <p>Renovar <b>apaga</b> o refresh usado e grava um novo. Um refresh token
 * serve uma vez so. Se alguem copiar o cookie e o dono renovar antes, o token
 * roubado ja nao esta na tabela e e recusado — e a vitima continua logada,
 * sem perceber nada.
 */
@Service
public class AuthService {

	private final AdminUserRepository usuarios;

	private final RefreshTokenRepository refreshTokens;

	private final PasswordEncoder senhas;

	private final JwtService jwt;

	private final Clock clock;

	public AuthService(AdminUserRepository usuarios, RefreshTokenRepository refreshTokens,
			PasswordEncoder senhas, JwtService jwt, Clock clock) {
		this.usuarios = usuarios;
		this.refreshTokens = refreshTokens;
		this.senhas = senhas;
		this.jwt = jwt;
		this.clock = clock;
	}

	/**
	 * Autentica e emite o par de tokens.
	 *
	 * <p><b>O login nao exige papel de admin.</b> Qualquer conta valida entra e
	 * recebe cookies; quem barra um {@code CLIENT} e a autorizacao das rotas
	 * {@code /admin}, que responde 403. E o desenho do Node, e e o correto:
	 * autenticacao e autorizacao sao perguntas diferentes, e misturar as duas
	 * aqui faria o login dizer "credencial invalida" a quem tem credencial
	 * valida.
	 */
	@Transactional
	public Sessao login(LoginRequest pedido) {
		Optional<AdminUser> encontrado = this.usuarios.findByEmail(pedido.email());

		// Comparacao mesmo sem usuario, contra um hash descartavel: sem isso o
		// tempo de resposta denuncia quais e-mails tem conta, porque bcrypt
		// leva ~100ms e "usuario nao existe" retornaria na hora.
		String hash = encontrado.map(AdminUser::getPasswordHash).orElse(HASH_DESCARTAVEL);
		boolean senhaConfere = this.senhas.matches(pedido.password(), hash);

		if (encontrado.isEmpty() || !senhaConfere) {
			throw new CredenciaisInvalidasException();
		}

		AdminUser usuario = encontrado.get();
		return emitirSessao(usuario, new LoginResponse(new LoginResponse.Usuario(
				usuario.getId(), usuario.getName(), usuario.getEmail())));
	}

	/**
	 * Troca um refresh token valido por um par novo.
	 *
	 * <p>Tres checagens, nessa ordem: a assinatura do JWT, a presenca do hash
	 * na tabela e o vencimento gravado na linha. A segunda e a que faz logout
	 * e rotacao valerem; sem ela, um token com assinatura boa seria aceito por
	 * sete dias mesmo depois de revogado.
	 *
	 * <p>O papel vem do <b>banco</b>, nao do token: o refresh carrega apenas
	 * {@code sub}. Quem for rebaixado de ADMIN para CLIENT perde o acesso no
	 * proximo refresh.
	 */
	@Transactional
	public Sessao renovar(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw SessaoInvalidaException.tokenAusente();
		}

		String userId = this.jwt.validarRefreshToken(refreshToken);
		if (userId == null) {
			throw SessaoInvalidaException.tokenInvalido();
		}

		String hash = this.jwt.hashDeArmazenamento(refreshToken);
		RefreshToken armazenado = this.refreshTokens.findByTokenHash(hash)
				.orElseThrow(SessaoInvalidaException::tokenInvalido);

		if (armazenado.getExpiresAt().isBefore(Instant.now(this.clock))) {
			// Linha vencida nao e apagada aqui: limpeza em massa e outra
			// conversa, e apagar no caminho de leitura esconderia o problema.
			throw SessaoInvalidaException.tokenInvalido();
		}

		AdminUser usuario = this.usuarios.findById(userId)
				.orElseThrow(SessaoInvalidaException::tokenInvalido);

		// Rotacao: o token usado morre aqui.
		this.refreshTokens.delete(armazenado);
		// O mutante que remove este flush sobrevive, e o status honesto e
		// NENHUM CENARIO DISTINGUIVEL ENCONTRADO -- nao "equivalente
		// provado". A diferenca de confianca importa: os sobreviventes de
		// SlotCalculator 175/180 tem demonstracao logica, e este tem apenas
		// uma busca sem resultado. O que foi procurado, e nao achado: leitura
		// de refresh_tokens depois do delete e antes do commit (o
		// emitirSessao so insere); colisao da insercao nova com a linha ainda
		// pendente de remocao, que exigiria hash igual, e o hash e de um
		// token recem-emitido; e visibilidade da linha por outra transacao,
		// que nao muda, porque antes do commit nenhuma das duas versoes a
		// expoe. Se alguem achar o cenario, o flush deixa de ser redundante e
		// este comentario e que estava errado. O delete da linha acima, por
		// outro lado, tem teste: rotacaoInvalidaOAnterior o mata.
		this.refreshTokens.flush();

		return emitirSessao(usuario, null);
	}

	/**
	 * Encerra a sessao, apagando o refresh token do banco.
	 *
	 * <p><b>Nunca falha.</b> Token ausente, malformado ou ja apagado levam ao
	 * mesmo lugar: o controller limpa os cookies de qualquer forma. Um logout
	 * que respondesse erro deixaria o usuario preso numa sessao que ele pediu
	 * para encerrar — o pior resultado possivel para esta operacao.
	 *
	 * <p>O access token continua tecnicamente valido ate expirar; e o preco de
	 * ser stateless, e o teto e de 15 minutos. O cookie e removido do
	 * navegador no mesmo instante.
	 */
	@Transactional
	public void logout(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}
		this.refreshTokens.findByTokenHash(this.jwt.hashDeArmazenamento(refreshToken))
				.ifPresent(this.refreshTokens::delete);
	}

	private Sessao emitirSessao(AdminUser usuario, LoginResponse corpo) {
		String access = this.jwt.emitirAccessToken(usuario);
		String refresh = this.jwt.emitirRefreshToken(usuario);

		RefreshToken linha = new RefreshToken();
		linha.setUser(usuario);
		// O token em claro nunca toca o banco.
		linha.setTokenHash(this.jwt.hashDeArmazenamento(refresh));
		linha.setExpiresAt(Instant.now(this.clock).plus(JwtService.VALIDADE_REFRESH));
		this.refreshTokens.saveAndFlush(linha);

		return new Sessao(access, refresh, corpo);
	}

	/**
	 * Hash de bcrypt bem formado que nao corresponde a senha alguma.
	 *
	 * <p>Existe so para dar trabalho ao {@code matches} quando o e-mail nao
	 * tem conta, igualando o tempo de resposta dos dois caminhos. O valor e
	 * irrelevante desde que seja um hash valido — este e de uma senha
	 * aleatoria descartada.
	 */
	private static final String HASH_DESCARTAVEL =
			"$2a$12$C6UzMDM.H6dfI/f/IKcEe.8VSeCpkxRXGb0BaIUHtvCPLHtLWwBHm";

	/**
	 * O resultado de um login ou refresh: os dois tokens e, no login, o corpo
	 * da resposta.
	 *
	 * <p>Os tokens saem daqui para virarem <b>cookies</b> no controller, nunca
	 * JSON. Este record e interno a aplicacao e nao e serializado.
	 *
	 * @param corpo {@code null} no refresh, que responde {@code data: null}
	 */
	public record Sessao(String accessToken, String refreshToken, LoginResponse corpo) {

	}

}
