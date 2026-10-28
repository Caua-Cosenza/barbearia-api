package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * Base das falhas previstas de negocio.
 *
 * <p>Uma {@code BusinessException} <b>nao e um defeito</b>: e uma situacao que
 * o sistema sabe que acontece — horario que acabou de ser tomado, profissional
 * que nao atende naquele dia, prazo de cancelamento vencido. Por isso ela
 * nasce ja sabendo as tres coisas que o cliente vai receber: o status HTTP, o
 * {@code error} do envelope e a {@code message} em portugues, pronta para
 * aparecer na tela.
 *
 * <p>Esse empacotamento e o que permite ao
 * {@code GlobalExceptionHandler} ter <b>um unico</b> {@code @ExceptionHandler}
 * para toda a hierarquia, em vez de um por subclasse com o status repetido na
 * anotacao. Os controllers nunca tem {@code try/catch}: lancam-se excecoes e o
 * advice traduz.
 *
 * <p>As mensagens sao as mesmas do backend Node, caractere a caractere, onde o
 * Node tinha uma. Onde nao tinha, a mensagem e nova e segue o mesmo tom.
 */
public abstract class BusinessException extends RuntimeException {

	private final HttpStatus status;

	private final String codigo;

	/**
	 * @param status o status HTTP da resposta
	 * @param codigo o campo {@code error} do envelope (ex.: {@code "Conflict"})
	 * @param mensagem o campo {@code message}, em portugues, visivel ao cliente
	 */
	protected BusinessException(HttpStatus status, String codigo, String mensagem) {
		super(mensagem);
		this.status = status;
		this.codigo = codigo;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

	public String getCodigo() {
		return this.codigo;
	}

}
