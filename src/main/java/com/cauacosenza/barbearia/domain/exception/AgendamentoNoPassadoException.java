package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 400 — a data e hora pedidas ja passaram.
 *
 * <p><b>Na pratica esta excecao nao chega ao cliente por HTTP</b>, e isso e
 * intencional: a mesma regra existe tambem na validacao do DTO, que roda antes
 * e devolve 422. E a estrutura do Node reproduzida tal como esta — la o
 * {@code .refine(v => new Date(v) > new Date())} do Zod sombreia o
 * {@code if (scheduledAt <= new Date())} do service, e so o 422 e observavel.
 *
 * <p>A duplicacao fica porque as duas camadas protegem coisas diferentes: a
 * validacao protege a borda HTTP, esta excecao protege o service de ser
 * chamado direto por codigo futuro (um job, um import, a Fase 6). Remover a
 * checagem do service por "ser inalcancavel" deixaria a regra dependendo de
 * quem chama.
 */
public class AgendamentoNoPassadoException extends BusinessException {

	public AgendamentoNoPassadoException() {
		super(HttpStatus.BAD_REQUEST, "Bad Request", "Não é possível agendar no passado");
	}

}
