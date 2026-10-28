package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 no cadastro de conta.
 *
 * <p>Esta excecao <b>conta</b> que o e-mail ja existe, ao contrario do login,
 * que nunca distingue os casos. E o mesmo comportamento do Node, e e um
 * compromisso consciente: sem ele nao da para explicar a quem tenta se
 * cadastrar por que o pedido falhou. A rota de cadastro tem limite de 5 por
 * minuto por IP, o que torna a enumeracao em massa cara.
 */
public class EmailJaCadastradoException extends BusinessException {

	public EmailJaCadastradoException() {
		super(HttpStatus.CONFLICT, "Conflict", "Email already registered");
	}

}
