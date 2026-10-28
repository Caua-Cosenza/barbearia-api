package com.cauacosenza.barbearia.domain.enums;

/**
 * Espelha o tipo enumerado {@code "Role"} do Postgres.
 *
 * <p>{@code CLIENT} e o default da coluna {@code users.role}. A ordem segue a
 * do banco, pelo mesmo motivo descrito em {@link AppointmentStatus}.
 */
public enum Role {

	CLIENT,

	PROFESSIONAL,

	ADMIN

}
