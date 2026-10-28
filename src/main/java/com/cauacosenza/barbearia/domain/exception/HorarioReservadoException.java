package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 — o horario colide com um bloqueio criado pelo proprio profissional
 * (folga, almoco, imprevisto).
 *
 * <p>Distinto de {@link SlotIndisponivelException}, que e outro cliente na
 * frente. Aqui nao ha concorrencia nenhuma: o horario simplesmente nao esta a
 * venda. Mensagem identica a do Node.
 */
public class HorarioReservadoException extends BusinessException {

	public HorarioReservadoException() {
		super(HttpStatus.CONFLICT, "Conflict", "Este horário está reservado pelo profissional");
	}

}
