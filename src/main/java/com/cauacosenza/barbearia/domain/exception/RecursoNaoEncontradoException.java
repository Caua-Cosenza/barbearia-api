package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 404 — o recurso pedido nao existe, ou existe e esta inativo.
 *
 * <p>Inativo e tratado como inexistente de proposito: um servico desativado ou
 * um profissional que saiu nao devem ser distinguiveis de um id inventado, ou
 * a API vira um oraculo sobre o que ja existiu no catalogo.
 *
 * <p>As mensagens vem das fabricas estaticas para que fiquem identicas as do
 * Node — que, nestes caminhos, respondia em ingles. Mantidas como estao: o
 * criterio desta fase e equivalencia, e sao mensagens que o frontend nunca
 * exibe (o axios rejeita 4xx e a tela mostra texto proprio).
 */
public class RecursoNaoEncontradoException extends BusinessException {

	private RecursoNaoEncontradoException(String mensagem) {
		super(HttpStatus.NOT_FOUND, "Not Found", mensagem);
	}

	public static RecursoNaoEncontradoException profissional() {
		return new RecursoNaoEncontradoException("Professional not found");
	}

	public static RecursoNaoEncontradoException servico() {
		return new RecursoNaoEncontradoException("Service not found");
	}

	public static RecursoNaoEncontradoException servico(String id) {
		return new RecursoNaoEncontradoException("Service not found: " + id);
	}

	public static RecursoNaoEncontradoException agendamento() {
		return new RecursoNaoEncontradoException("Agendamento não encontrado");
	}

	public static RecursoNaoEncontradoException bloqueio() {
		return new RecursoNaoEncontradoException("Blocked slot not found");
	}

}
