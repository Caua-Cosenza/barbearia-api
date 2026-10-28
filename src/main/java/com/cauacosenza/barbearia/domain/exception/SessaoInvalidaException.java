package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 401 no fluxo de refresh.
 *
 * <p>Tres situacoes distintas, uma resposta so — pelo mesmo motivo de
 * {@link CredenciaisInvalidasException}: distinguir "token malformado" de
 * "token revogado" diria a quem roubou um cookie se ele ainda vale.
 *
 * <ul>
 *   <li>{@link #tokenAusente()} — nao veio cookie {@code refresh_token}. E o
 *       caso normal de quem nunca logou, ou de quem logou ha mais de sete
 *       dias.
 *   <li>{@link #tokenInvalido()} — assinatura ou formato errados, <b>ou</b> o
 *       hash nao esta mais em {@code refresh_tokens}: foi usado (a rotacao
 *       apaga o anterior) ou o usuario saiu.
 * </ul>
 */
public class SessaoInvalidaException extends BusinessException {

	private SessaoInvalidaException(String mensagem) {
		super(HttpStatus.UNAUTHORIZED, "Unauthorized", mensagem);
	}

	public static SessaoInvalidaException tokenAusente() {
		return new SessaoInvalidaException("Refresh token missing");
	}

	public static SessaoInvalidaException tokenInvalido() {
		return new SessaoInvalidaException("Refresh token expired or revoked");
	}

}
