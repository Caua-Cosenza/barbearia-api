package com.cauacosenza.barbearia.domain.exception;

/**
 * Falha ao cifrar ou decifrar PII.
 *
 * <p><b>Nenhuma mensagem desta excecao pode conter o plaintext, o ciphertext,
 * o IV ou a chave</b> — apenas o que falhou. As colunas cifradas guardam nome,
 * telefone e e-mail de clientes: um log com esse conteudo, cifrado ou em
 * claro, e incidente de LGPD.
 *
 * <p>Por isso esta excecao tambem nao encadeia a causa original quando ela e
 * uma excecao do JCE que possa carregar dado da entrada. Quando a causa e
 * segura de propagar, ela vem como {@code cause} para nao perder o rastro.
 */
public class CryptoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CryptoException(String message) {
		super(message);
	}

	public CryptoException(String message, Throwable cause) {
		super(message, cause);
	}

}
