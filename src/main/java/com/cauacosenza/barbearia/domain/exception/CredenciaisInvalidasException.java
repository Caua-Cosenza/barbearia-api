package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 401 — e-mail ou senha errados.
 *
 * <p><b>Uma mensagem so para os dois casos</b>, e isso e deliberado. Dizer
 * "usuario nao encontrado" para um e-mail inexistente e "senha incorreta" para
 * um existente transforma o login em um oraculo: qualquer um descobre quais
 * e-mails tem conta testando um por um. A unica resposta e
 * {@code "Invalid credentials"}, literal do Node.
 *
 * <p>O {@code AuthService} complementa isso comparando a senha contra um hash
 * descartavel quando o usuario nao existe, para que o tempo de resposta
 * tambem nao entregue a diferenca.
 */
public class CredenciaisInvalidasException extends BusinessException {

	public CredenciaisInvalidasException() {
		super(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid credentials");
	}

}
