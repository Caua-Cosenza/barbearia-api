package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 400 — o profissional nao tem jornada cadastrada neste dia da semana.
 *
 * <p>A ausencia de linha em {@code professional_availability} <b>e</b> a
 * regra, nao um dado faltando: e assim que sexta e sabado funcionam por ordem
 * de chegada e domingo e fechado. Na leitura isso devolve lista vazia de
 * horarios; na escrita tem que recusar, ou a API aceita o que a tela nunca
 * ofereceu.
 */
public class ProfissionalIndisponivelException extends BusinessException {

	public ProfissionalIndisponivelException() {
		super(HttpStatus.BAD_REQUEST, "Bad Request", "Profissional não atende neste dia");
	}

}
