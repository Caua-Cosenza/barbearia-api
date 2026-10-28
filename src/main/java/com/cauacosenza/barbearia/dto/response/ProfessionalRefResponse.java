package com.cauacosenza.barbearia.dto.response;

import com.cauacosenza.barbearia.domain.entity.Barber;

/**
 * O profissional reduzido a id e nome, como aparece dentro da resposta de
 * criacao de agendamento.
 *
 * <p>O nome sai decifrado: a coluna e {@code name_encrypted} e o
 * {@code EncryptedStringConverter} ja o entregou em texto claro a entity.
 */
public record ProfessionalRefResponse(String id, String name) {

	public static ProfessionalRefResponse de(Barber barbeiro) {
		return new ProfessionalRefResponse(barbeiro.getId(), barbeiro.getName());
	}

}
