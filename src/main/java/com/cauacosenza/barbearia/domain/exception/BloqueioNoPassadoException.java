package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 400 — tentativa de reservar horario em um dia que ja passou.
 *
 * <p>A comparacao e contra <b>hoje em Brasilia</b>, nao em UTC. Com UTC, todo
 * bloqueio criado depois das 21h para o dia seguinte seria recusado, porque em
 * UTC "amanha" ja comecou. Foi um bug real.
 *
 * <p>Mensagem identica a do Node, que responde em ingles neste caminho.
 */
public class BloqueioNoPassadoException extends BusinessException {

	public BloqueioNoPassadoException() {
		super(HttpStatus.BAD_REQUEST, "Bad Request", "Cannot block slots in the past");
	}

}
